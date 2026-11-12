package com.jexcode.snapshot;

import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SideGitManagerTest {

    @TempDir
    Path tempDir;

    @Test
    void restoresTrackedFilesToPreTurnSnapshot() throws Exception {
        Path project = tempDir.resolve("project");
        Path snapshots = tempDir.resolve("snapshots");
        Files.createDirectories(project);
        Files.writeString(project.resolve("a.txt"), "before");

        SideGitManager manager = new SideGitManager(project,
                new SnapshotConfig(true, snapshots, 50, List.of(".git", "target", "*.class")));
        manager.preTurnSnapshot("turn-1", "before task");

        Files.writeString(project.resolve("a.txt"), "after");
        Files.writeString(project.resolve("new.txt"), "new file");
        manager.postTurnSnapshot("turn-1", "after task");

        RestoreResult result = manager.restorePreTurn(1);

        assertTrue(result.success());
        assertEquals("before", Files.readString(project.resolve("a.txt")));
        assertFalse(Files.exists(project.resolve("new.txt")));
        assertTrue(Files.exists(manager.gitDir().resolve("config")));
        assertFalse(Files.exists(project.resolve(".git")));
    }

    @Test
    void preservesExistingGitFile(@TempDir Path externalGitDir) throws Exception {
        Path project = Files.createDirectory(tempDir.resolve("project"));
        Path gitFile = project.resolve(".git");
        Path actualGitDir = externalGitDir.resolve(".git");
        try (Git git = Git.init().setDirectory(project.toFile()).setGitDir(actualGitDir.toFile()).call()) {
            String originalGitFile = Files.readString(gitFile);
            Files.writeString(project.resolve("a.txt"), "content");

            SideGitManager manager = new SideGitManager(project,
                    new SnapshotConfig(true, tempDir.resolve("snapshots"), 50, List.of(".git")));
            manager.preTurnSnapshot("turn-1", "before task");

            assertEquals(originalGitFile, Files.readString(gitFile));
            assertTrue(git.status().call().getUntracked().contains("a.txt"));
        }
    }

    @Test
    void serviceWritesPostTurnSnapshotAsynchronously() throws Exception {
        Path project = tempDir.resolve("project");
        Path snapshots = tempDir.resolve("snapshots");
        Files.createDirectories(project);
        SnapshotConfig config = new SnapshotConfig(true, snapshots, 50, List.of(".git", "target"));
        SnapshotService service = new SnapshotService(new SideGitManager(project, config));

        String output = service.runTurn("react", "write file", () -> {
            Files.writeString(project.resolve("a.txt"), "created");
            return "ok";
        });
        service.awaitIdle();

        assertEquals("ok", output);
        List<TurnSnapshot> all = service.listSnapshots(10);
        assertEquals(2, all.size());
        assertEquals(SnapshotPhase.POST_TURN, all.get(0).phase());
        assertEquals(SnapshotPhase.PRE_TURN, all.get(1).phase());
    }
}
