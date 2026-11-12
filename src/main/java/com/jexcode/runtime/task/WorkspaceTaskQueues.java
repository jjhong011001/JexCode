package com.jexcode.runtime.task;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

public final class WorkspaceTaskQueues implements AutoCloseable {
    private final Function<Path, TaskRunner> runnerForWorkspace;
    private final Map<Path, DurableTaskManager> queues = new HashMap<>();
    private boolean closed;

    public WorkspaceTaskQueues(Function<Path, TaskRunner> runnerForWorkspace) {
        this.runnerForWorkspace = runnerForWorkspace;
    }

    public synchronized DurableTaskManager forWorkspace(Path path) {
        if (closed) {
            throw new IllegalStateException("后台任务队列已关闭");
        }
        try {
            Path workspace = path.toRealPath();
            DurableTaskManager queue = queues.get(workspace);
            if (queue != null) {
                return queue;
            }
            queue = DurableTaskManager.openDefault(workspace, runnerForWorkspace.apply(workspace));
            queue.start();
            queues.put(workspace, queue);
            return queue;
        } catch (IOException | SQLException e) {
            throw new IllegalStateException("无法打开后台任务队列: " + e.getMessage(), e);
        }
    }

    @Override
    public synchronized void close() {
        closed = true;
        queues.values().forEach(DurableTaskManager::close);
        queues.clear();
    }
}
