package com.jexcode.runtime.task;

import java.io.Closeable;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.Set;

final class TaskQueueLease implements Closeable {
    // Keep one channel per lock file in this JVM. Closing a second channel to the same
    // file can release the process's existing lock on some platforms.
    private static final Set<Path> HELD_PATHS = new HashSet<>();

    private final Path path;
    private final FileChannel channel;

    private TaskQueueLease(Path path, FileChannel channel) {
        this.path = path;
        this.channel = channel;
    }

    static synchronized TaskQueueLease tryAcquire(Path path) throws IOException {
        if (HELD_PATHS.contains(path)) {
            return null;
        }
        FileChannel channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        boolean acquired = false;
        try {
            if (channel.tryLock() == null) {
                return null;
            }
            HELD_PATHS.add(path);
            acquired = true;
            return new TaskQueueLease(path, channel);
        } finally {
            if (!acquired) {
                channel.close();
            }
        }
    }

    @Override
    public void close() throws IOException {
        synchronized (TaskQueueLease.class) {
            try {
                channel.close();
            } finally {
                HELD_PATHS.remove(path);
            }
        }
    }
}
