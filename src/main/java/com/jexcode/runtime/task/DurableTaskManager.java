package com.jexcode.runtime.task;

import com.jexcode.runtime.CancellationContext;
import com.jexcode.runtime.CancellationToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class DurableTaskManager implements Closeable {
    private static final Logger log = LoggerFactory.getLogger(DurableTaskManager.class);
    private static final long POLL_MILLIS = 300;

    private final Path dbPath;
    private final TaskRunner runner;
    private final int workerCount;
    private final Connection connection;
    private final Map<String, RunningTask> runningTasks = new HashMap<>();
    private TaskQueueLease lease;
    private ExecutorService workers;
    private volatile boolean running;
    private boolean closed;

    public DurableTaskManager(Path dbPath, TaskRunner runner, int workerCount) throws SQLException {
        this.runner = runner;
        this.workerCount = Math.max(1, workerCount);
        try {
            Path absolute = dbPath.toAbsolutePath().normalize();
            Files.createDirectories(absolute.getParent());
            this.dbPath = Files.exists(absolute) ? absolute.toRealPath()
                    : absolute.getParent().toRealPath().resolve(absolute.getFileName());
        } catch (IOException e) {
            throw new SQLException("无法准备任务数据库路径: " + e.getMessage(), e);
        }
        this.connection = DriverManager.getConnection("jdbc:sqlite:" + this.dbPath);
        try {
            initTables();
            acquireLease();
        } catch (SQLException e) {
            try {
                connection.close();
            } catch (SQLException closeError) {
                e.addSuppressed(closeError);
            }
            throw e;
        }
    }

    public static DurableTaskManager openDefault(TaskRunner runner) throws SQLException {
        return new DurableTaskManager(defaultDbPath(), runner, workerCount());
    }

    public static Path defaultDbPath() {
        try {
            return projectDbPath(defaultTaskRoot(), Path.of("."));
        } catch (IOException e) {
            throw new IllegalStateException("无法读取后台任务工作区: " + e.getMessage(), e);
        }
    }

    public static DurableTaskManager openDefault(Path workspace, TaskRunner runner) throws SQLException {
        try {
            return new DurableTaskManager(projectDbPath(defaultTaskRoot(), workspace), runner, workerCount());
        } catch (IOException e) {
            throw new SQLException("无法读取后台任务工作区: " + e.getMessage(), e);
        }
    }

    static Path projectDbPath(Path taskRoot, Path workspace) throws IOException {
        Path root = workspace.toRealPath();
        if (!Files.isDirectory(root)) {
            throw new IOException("工作区路径不是目录: " + root);
        }
        String workspaceId = UUID.nameUUIDFromBytes(root.toString().getBytes(StandardCharsets.UTF_8)).toString();
        return taskRoot.resolve(workspaceId).resolve("tasks.db");
    }

    private static Path defaultTaskRoot() {
        String configured = System.getProperty("jexcode.task.dir");
        if (configured == null || configured.isBlank()) {
            configured = System.getenv("JEXCODE_TASK_DIR");
        }
        if (configured == null || configured.isBlank()) {
            configured = Path.of(System.getProperty("user.home"), ".jexcode", "tasks").toString();
        }
        return Path.of(configured);
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
        var executor = new ScheduledThreadPoolExecutor(workerCount + 1, r -> {
            Thread thread = new Thread(r, "jexcode-task-worker");
            thread.setDaemon(true);
            return thread;
        }) {
            @Override
            protected void terminated() {
                closeStorage();
            }
        };
        workers = executor;
        for (int i = 0; i < workerCount; i++) {
            executor.submit(this::workerLoop);
        }
        executor.scheduleWithFixedDelay(this::observeCanceledTasks, POLL_MILLIS, POLL_MILLIS, TimeUnit.MILLISECONDS);
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
        if (!markTerminal(task, TaskStatus.CANCELED, task.result(), "用户取消", true)) {
            return false;
        }
        interruptTask(runningTasks.get(task.id()));
        notifyAll();
        return true;
    }

    public Path dbPath() {
        return dbPath;
    }

    private void workerLoop() {
        try {
            while (running) {
                try {
                    RunningTask active = claimNext();
                    if (active == null) {
                        waitForWork();
                    } else {
                        runClaimedTask(active);
                    }
                } catch (SQLException | IllegalStateException e) {
                    log.warn("Background queue operation failed for {}: {}", dbPath, e.getMessage());
                    waitForWork();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private synchronized void waitForWork() throws InterruptedException {
        if (running) {
            wait(POLL_MILLIS);
        }
    }

    private void runClaimedTask(RunningTask active) {
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
    }

    private synchronized RunningTask claimNext() throws SQLException {
        if (!running || !acquireLease()) {
            return null;
        }
        DurableTask task;
        String now = Instant.now().toString();
        try (PreparedStatement claim = connection.prepareStatement("""
                UPDATE runtime_tasks
                SET status = ?, started_at = ?, updated_at = ?
                WHERE id = (
                    SELECT id FROM runtime_tasks WHERE status = ? ORDER BY created_at ASC LIMIT 1
                )
                RETURNING *
                """)) {
            claim.setString(1, TaskStatus.RUNNING.value());
            claim.setString(2, now);
            claim.setString(3, now);
            claim.setString(4, TaskStatus.ENQUEUED.value());
            try (ResultSet rs = claim.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                task = fromRow(rs);
            }
        }
        RunningTask active = new RunningTask(task, new CancellationToken(), Thread.currentThread());
        runningTasks.put(task.id(), active);
        return active;
    }

    private synchronized void finishRunningTask(DurableTask task, TaskStatus status, String result, String error) {
        if (running) {
            markTerminal(task, status, result, error, false);
        }
    }

    private boolean markTerminal(DurableTask task, TaskStatus status, String result, String error,
                                 boolean includeQueued) {
        String now = Instant.now().toString();
        Instant startedAt = task.startedAt();
        long durationMs = startedAt == null ? 0 : Math.max(0, Instant.now().toEpochMilli() - startedAt.toEpochMilli());
        try (PreparedStatement ps = connection.prepareStatement("""
                UPDATE runtime_tasks
                SET status = ?, result = ?, error = ?, finished_at = ?, duration_ms = ?, updated_at = ?
                WHERE id = ? AND status IN (?, ?)
                """)) {
            ps.setString(1, status.value());
            ps.setString(2, result == null ? "" : result);
            ps.setString(3, error);
            ps.setString(4, now);
            ps.setLong(5, durationMs);
            ps.setString(6, now);
            ps.setString(7, task.id());
            ps.setString(8, TaskStatus.RUNNING.value());
            ps.setString(9, includeQueued ? TaskStatus.ENQUEUED.value() : TaskStatus.RUNNING.value());
            return ps.executeUpdate() != 0;
        } catch (SQLException e) {
            throw new IllegalStateException("更新后台任务失败: " + e.getMessage(), e);
        }
    }

    private synchronized void observeCanceledTasks() {
        if (!running) {
            return;
        }
        try {
            for (RunningTask active : runningTasks.values()) {
                if (!active.token().isCancelled() && find(active.task().id())
                        .filter(task -> task.status() == TaskStatus.CANCELED).isPresent()) {
                    interruptTask(active);
                }
            }
        } catch (IllegalStateException e) {
            log.warn("Could not check for canceled background tasks in {}: {}", dbPath, e.getMessage());
        }
    }

    private static void interruptTask(RunningTask active) {
        if (active != null && !active.token().isCancelled()) {
            active.token().cancel();
            active.worker().interrupt();
        }
    }

    private boolean acquireLease() throws SQLException {
        if (lease != null) {
            return true;
        }
        TaskQueueLease candidate;
        try {
            candidate = TaskQueueLease.tryAcquire(dbPath.resolveSibling(dbPath.getFileName() + ".lock"));
        } catch (IOException e) {
            throw new SQLException("无法锁定后台任务队列: " + dbPath, e);
        }
        if (candidate == null) {
            return false;
        }
        try {
            recoverRunningTasks();
            lease = candidate;
            return true;
        } catch (SQLException e) {
            try {
                candidate.close();
            } catch (IOException closeError) {
                e.addSuppressed(closeError);
            }
            throw e;
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
                SET status = ?, started_at = NULL, updated_at = ?
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
        if (executor == null) {
            closeStorage();
            return;
        }
        executor.shutdownNow();
        try {
            executor.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // A replacement worker can recover unfinished tasks only after this executor has exited.
    private synchronized void closeStorage() {
        try {
            connection.close();
        } catch (SQLException e) {
            log.warn("Could not close background task database {}: {}", dbPath, e.getMessage());
        }
        if (lease != null) {
            try {
                lease.close();
            } catch (IOException e) {
                log.warn("Could not release background queue lock for {}: {}", dbPath, e.getMessage());
            }
            lease = null;
        }
    }

    private record RunningTask(DurableTask task, CancellationToken token, Thread worker) {}
}
