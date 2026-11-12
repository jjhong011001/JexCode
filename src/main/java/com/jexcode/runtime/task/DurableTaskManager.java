package com.jexcode.runtime.task;

import com.jexcode.runtime.CancellationContext;
import com.jexcode.runtime.CancellationToken;

import java.io.Closeable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.HashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class DurableTaskManager implements Closeable {
    private final Path dbPath;
    private final TaskRunner runner;
    private final int workerCount;
    private final Connection connection;
    private final Map<String, RunningTask> runningTasks = new HashMap<>();
    private ExecutorService workers;
    private volatile boolean running;
    private boolean closed;

    public DurableTaskManager(Path dbPath, TaskRunner runner, int workerCount) throws SQLException {
        this.dbPath = dbPath;
        this.runner = runner;
        this.workerCount = Math.max(1, workerCount);
        try {
            Files.createDirectories(dbPath.getParent());
        } catch (Exception e) {
            throw new SQLException("无法创建任务数据库目录: " + e.getMessage(), e);
        }
        this.connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
        initTables();
        recoverRunningTasks();
    }

    public static DurableTaskManager openDefault(TaskRunner runner) throws SQLException {
        return new DurableTaskManager(defaultDbPath(), runner, workerCount());
    }

    public static Path defaultDbPath() {
        String configured = System.getProperty("jexcode.task.dir");
        if (configured == null || configured.isBlank()) {
            configured = System.getenv("JEXCODE_TASK_DIR");
        }
        if (configured == null || configured.isBlank()) {
            configured = Path.of(System.getProperty("user.home"), ".jexcode", "tasks").toString();
        }
        return Path.of(configured).resolve("tasks.db");
    }

    private static int workerCount() {
        String configured = System.getProperty("jexcode.task.workers");
        if (configured == null || configured.isBlank()) {
            configured = System.getenv("JEXCODE_TASK_WORKERS");
        }
        if (configured == null || configured.isBlank()) {
            return 2;
        }
        try {
            return Math.max(1, Integer.parseInt(configured.trim()));
        } catch (NumberFormatException e) {
            return 2;
        }
    }

    public synchronized void start() {
        if (closed) {
            throw new IllegalStateException("后台任务管理器已关闭");
        }
        if (running) {
            return;
        }
        running = true;
        workers = Executors.newFixedThreadPool(workerCount, r -> {
            Thread thread = new Thread(r, "jexcode-task-worker");
            thread.setDaemon(true);
            return thread;
        });
        for (int i = 0; i < workerCount; i++) {
            workers.submit(this::workerLoop);
        }
    }

    public synchronized DurableTask enqueue(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalArgumentException("任务内容不能为空");
        }
        String id = "task_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String now = Instant.now().toString();
        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO runtime_tasks (id, status, prompt, created_at)
                VALUES (?, ?, ?, ?)
                """)) {
            ps.setString(1, id);
            ps.setString(2, TaskStatus.ENQUEUED.value());
            ps.setString(3, prompt.trim());
            ps.setString(4, now);
            ps.executeUpdate();
            notifyAll();
            return find(id).orElseThrow();
        } catch (SQLException e) {
            throw new IllegalStateException("提交后台任务失败: " + e.getMessage(), e);
        }
    }

    public synchronized List<DurableTask> list(int limit) {
        int bounded = Math.max(1, Math.min(limit, 100));
        List<DurableTask> tasks = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement("""
                SELECT * FROM runtime_tasks
                ORDER BY created_at DESC
                LIMIT ?
                """)) {
            ps.setInt(1, bounded);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    tasks.add(fromRow(rs));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("读取后台任务失败: " + e.getMessage(), e);
        }
        return tasks;
    }

    public synchronized Optional<DurableTask> find(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        try (PreparedStatement ps = connection.prepareStatement("SELECT * FROM runtime_tasks WHERE id = ?")) {
            ps.setString(1, id.trim());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(fromRow(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("读取后台任务失败: " + e.getMessage(), e);
        }
    }

    public synchronized boolean cancel(String id) {
        Optional<DurableTask> current = find(id);
        if (current.isEmpty() || current.get().terminal()) {
            return false;
        }
        DurableTask task = current.get();
        markTerminal(task.id(), TaskStatus.CANCELED, task.result(), "用户取消", task.startedAt());
        RunningTask active = runningTasks.get(task.id());
        if (active != null) {
            active.token().cancel();
            active.worker().interrupt();
        }
        notifyAll();
        return true;
    }

    public Path dbPath() {
        return dbPath;
    }

    private void workerLoop() {
        while (running) {
            try {
                RunningTask active = claimNext();
                if (active == null) {
                    synchronized (this) {
                        if (running) {
                            wait(300);
                        }
                    }
                    continue;
                }
                DurableTask task = active.task();
                try {
                    if (!active.token().isCancelled()) {
                        String result = CancellationContext.call(active.token(), () -> runner.run(task.prompt()));
                        finishRunningTask(task, TaskStatus.COMPLETED, result, null);
                    }
                } catch (InterruptedException e) {
                    finishRunningTask(task, TaskStatus.CANCELED, "", "任务线程被中断");
                } catch (Exception e) {
                    finishRunningTask(task, TaskStatus.FAILED, "", e.getMessage());
                } finally {
                    synchronized (this) {
                        runningTasks.remove(task.id());
                        // Interrupts requested for this task must not carry over to the next task.
                        Thread.interrupted();
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception ignored) {
                // Worker loop must stay alive; individual failures are recorded on the task row when possible.
            }
        }
    }

    private synchronized RunningTask claimNext() throws SQLException {
        if (!running) {
            return null;
        }
        connection.setAutoCommit(false);
        try {
            DurableTask task = null;
            try (PreparedStatement select = connection.prepareStatement("""
                    SELECT * FROM runtime_tasks
                    WHERE status = ?
                    ORDER BY created_at ASC
                    LIMIT 1
                    """)) {
                select.setString(1, TaskStatus.ENQUEUED.value());
                try (ResultSet rs = select.executeQuery()) {
                    if (rs.next()) {
                        task = fromRow(rs);
                    }
                }
            }
            if (task == null) {
                connection.commit();
                return null;
            }
            String now = Instant.now().toString();
            try (PreparedStatement update = connection.prepareStatement("""
                    UPDATE runtime_tasks
                    SET status = ?, started_at = ?, updated_at = ?
                    WHERE id = ? AND status = ?
                    """)) {
                update.setString(1, TaskStatus.RUNNING.value());
                update.setString(2, now);
                update.setString(3, now);
                update.setString(4, task.id());
                update.setString(5, TaskStatus.ENQUEUED.value());
                if (update.executeUpdate() == 0) {
                    connection.rollback();
                    return null;
                }
            }
            connection.commit();
            DurableTask claimed = find(task.id()).orElseThrow();
            RunningTask active = new RunningTask(claimed, new CancellationToken(), Thread.currentThread());
            runningTasks.put(claimed.id(), active);
            return active;
        } catch (SQLException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    private synchronized void finishRunningTask(DurableTask task, TaskStatus status, String result, String error) {
        if (running && find(task.id()).filter(latest -> latest.status() == TaskStatus.RUNNING).isPresent()) {
            markTerminal(task.id(), status, result, error, task.startedAt());
        }
    }

    private synchronized void markTerminal(String id, TaskStatus status, String result, String error, Instant startedAt) {
        String now = Instant.now().toString();
        long durationMs = startedAt == null ? 0 : Math.max(0, Instant.now().toEpochMilli() - startedAt.toEpochMilli());
        try (PreparedStatement ps = connection.prepareStatement("""
                UPDATE runtime_tasks
                SET status = ?, result = ?, error = ?, finished_at = ?, duration_ms = ?, updated_at = ?
                WHERE id = ?
                """)) {
            ps.setString(1, status.value());
            ps.setString(2, result == null ? "" : result);
            ps.setString(3, error);
            ps.setString(4, now);
            ps.setLong(5, durationMs);
            ps.setString(6, now);
            ps.setString(7, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("更新后台任务失败: " + e.getMessage(), e);
        }
    }

    private void initTables() throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS runtime_tasks (
                        id TEXT PRIMARY KEY,
                        status TEXT NOT NULL,
                        prompt TEXT NOT NULL,
                        result TEXT,
                        error TEXT,
                        created_at TEXT NOT NULL,
                        started_at TEXT,
                        finished_at TEXT,
                        updated_at TEXT,
                        duration_ms INTEGER DEFAULT 0
                    )
                    """);
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_runtime_tasks_status ON runtime_tasks(status)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_runtime_tasks_created ON runtime_tasks(created_at)");
        }
    }

    private synchronized void recoverRunningTasks() throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("""
                UPDATE runtime_tasks
                SET status = ?, updated_at = ?
                WHERE status = ?
                """)) {
            ps.setString(1, TaskStatus.ENQUEUED.value());
            ps.setString(2, Instant.now().toString());
            ps.setString(3, TaskStatus.RUNNING.value());
            ps.executeUpdate();
        }
    }

    private DurableTask fromRow(ResultSet rs) throws SQLException {
        return new DurableTask(
                rs.getString("id"),
                TaskStatus.from(rs.getString("status")),
                rs.getString("prompt"),
                rs.getString("result"),
                rs.getString("error"),
                parseInstant(rs.getString("created_at")),
                parseInstant(rs.getString("started_at")),
                parseInstant(rs.getString("finished_at")),
                rs.getLong("duration_ms")
        );
    }

    private static Instant parseInstant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return Instant.parse(value);
    }

    @Override
    public void close() {
        ExecutorService executor;
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            running = false;
            runningTasks.values().forEach(active -> active.token().cancel());
            executor = workers;
            notifyAll();
        }
        if (executor != null) {
            executor.shutdownNow();
            try {
                executor.awaitTermination(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        synchronized (this) {
            try {
                connection.close();
            } catch (SQLException ignored) {
            }
        }
    }

    private record RunningTask(DurableTask task, CancellationToken token, Thread worker) {}
}
