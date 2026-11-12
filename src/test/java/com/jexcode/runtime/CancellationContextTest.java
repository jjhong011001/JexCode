package com.jexcode.runtime;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class CancellationContextTest {

    @Test
    void childThreadKeepsRunTokenAfterParentLeavesScope() throws Exception {
        CancellationToken token = new CancellationToken();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch parentCleared = new CountDownLatch(1);
        try {
            Future<Boolean> result = CancellationContext.call(token, () -> executor.submit(() -> {
                assertTrue(parentCleared.await(5, TimeUnit.SECONDS));
                return CancellationContext.current() == token;
            }));

            parentCleared.countDown();

            assertNull(CancellationContext.current());
            assertTrue(result.get(5, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void cancelledRunDoesNotLeakIntoUnrelatedOrReusedWorker() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CancellationToken first = new CancellationToken();
        CancellationToken second = new CancellationToken();
        try {
            assertNull(executor.submit(CancellationContext::current).get(5, TimeUnit.SECONDS));

            CancellationContext.call(first, () -> {
                first.cancel();
                assertFalse(executor.submit(CancellationContext::isCancelled).get(5, TimeUnit.SECONDS));
                return null;
            });

            assertTrue(executor.submit(() ->
                    CancellationContext.call(first, CancellationContext::isCancelled)).get(5, TimeUnit.SECONDS));
            assertFalse(executor.submit(() ->
                    CancellationContext.call(second, CancellationContext::isCancelled)).get(5, TimeUnit.SECONDS));
            assertNull(executor.submit(CancellationContext::current).get(5, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
    }
}
