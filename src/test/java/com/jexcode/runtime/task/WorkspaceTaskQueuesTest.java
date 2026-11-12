package com.jexcode.runtime.task;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class WorkspaceTaskQueuesTest {
    @Test
    void routesTasksToTheirOwnWorkspacesAndLeavesLegacyQueueIntact(@TempDir Path tempDir) throws Exception {
        Path taskRoot = tempDir.resolve("tasks");
        Path legacyDb = taskRoot.resolve("tasks.db");
        Path first = Files.createDirectory(tempDir.resolve("first")).toRealPath();
        Path second = Files.createDirectory(tempDir.resolve("second")).toRealPath();
        DurableTask legacyTask;
        try (DurableTaskManager legacy = new DurableTaskManager(legacyDb, prompt -> prompt, 1)) {
            legacyTask = legacy.enqueue("legacy");
        }
        CountDownLatch wroteMarkers = new CountDownLatch(2);
        String oldRoot = System.setProperty("jexcode.task.dir", taskRoot.toString());
        try (WorkspaceTaskQueues queues = new WorkspaceTaskQueues(workspace -> prompt -> {
            Files.writeString(workspace.resolve("task.txt"), prompt);
            wroteMarkers.countDown();
            return prompt;
        })) {
            DurableTaskManager firstQueue = queues.forWorkspace(first);
            DurableTask firstTask = firstQueue.enqueue("from first");
            DurableTaskManager secondQueue = queues.forWorkspace(second);
            DurableTask secondTask = secondQueue.enqueue("from second");

            assertSame(firstQueue, queues.forWorkspace(first.resolve(".")));
            assertNotEquals(firstQueue.dbPath(), secondQueue.dbPath());
            assertTrue(firstQueue.find(secondTask.id()).isEmpty());
            assertTrue(secondQueue.find(firstTask.id()).isEmpty());
            assertTrue(firstQueue.find(legacyTask.id()).isEmpty());
            assertTrue(secondQueue.find(legacyTask.id()).isEmpty());
            assertTrue(wroteMarkers.await(5, TimeUnit.SECONDS));
            assertEquals("from first", Files.readString(first.resolve("task.txt")));
            assertEquals("from second", Files.readString(second.resolve("task.txt")));
        } finally {
            if (oldRoot == null) {
                System.clearProperty("jexcode.task.dir");
            } else {
                System.setProperty("jexcode.task.dir", oldRoot);
            }
        }
        try (DurableTaskManager legacy = new DurableTaskManager(legacyDb, prompt -> prompt, 1)) {
            assertEquals(TaskStatus.ENQUEUED, legacy.find(legacyTask.id()).orElseThrow().status());
        }
    }
}
