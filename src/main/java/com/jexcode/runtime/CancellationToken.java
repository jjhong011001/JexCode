package com.jexcode.runtime;

import java.util.ArrayList;
import java.util.List;

public class CancellationToken {
    private final List<Runnable> listeners = new ArrayList<>();
    private volatile boolean cancelled;

    public void cancel() {
        List<Runnable> pending;
        synchronized (listeners) {
            if (cancelled) {
                return;
            }
            cancelled = true;
            pending = List.copyOf(listeners);
            listeners.clear();
        }
        pending.forEach(Runnable::run);
    }

    public boolean isCancelled() {
        return cancelled;
    }

    /** Registers an action and returns a cleanup action to unregister it. */
    public Runnable onCancel(Runnable listener) {
        synchronized (listeners) {
            if (!cancelled) {
                listeners.add(listener);
                return () -> {
                    synchronized (listeners) {
                        listeners.remove(listener);
                    }
                };
            }
        }
        listener.run();
        return () -> {};
    }
}
