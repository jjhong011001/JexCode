package com.jexcode.runtime;

import java.util.concurrent.Callable;

public final class CancellationContext {
    // Per-call tool executors create fresh child threads. Reused workers bind explicitly.
    private static final InheritableThreadLocal<CancellationToken> LOCAL = new InheritableThreadLocal<>();

    private CancellationContext() {
    }

    public static CancellationToken current() {
        return LOCAL.get();
    }

    public static boolean isCancelled() {
        CancellationToken token = current();
        return Thread.currentThread().isInterrupted() || (token != null && token.isCancelled());
    }

    public static <T> T call(CancellationToken token, Callable<T> task) throws Exception {
        CancellationToken previous = LOCAL.get();
        LOCAL.set(token);
        try {
            return task.call();
        } finally {
            if (previous == null) {
                LOCAL.remove();
            } else {
                LOCAL.set(previous);
            }
        }
    }
}
