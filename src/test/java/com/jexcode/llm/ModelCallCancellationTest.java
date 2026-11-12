package com.jexcode.llm;

import com.jexcode.runtime.CancellationContext;
import com.jexcode.runtime.CancellationToken;
import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ModelCallCancellationTest {

    @Test
    void cancelsActiveCallAndLeavesNextCallUsable() throws Exception {
        CountDownLatch requestStarted = new CountDownLatch(1);
        AtomicReference<Call> completedCall = new AtomicReference<>();
        OkHttpClient httpClient = new OkHttpClient.Builder().addInterceptor(chain -> {
            if (requestStarted.getCount() > 0) {
                requestStarted.countDown();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (!chain.call().isCanceled() && System.nanoTime() < deadline) {
                    try {
                        Thread.sleep(10);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IOException(e);
                    }
                }
                throw new IOException(chain.call().isCanceled() ? "cancelled" : "not cancelled");
            }

            completedCall.set(chain.call());
            return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(200).message("OK")
                    .body(ResponseBody.create(
                            "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}\n\ndata: [DONE]\n", null))
                    .build();
        }).build();
        LlmClient client = new GLMClient("test") {
            @Override
            protected OkHttpClient httpClient() {
                return httpClient;
            }
        };

        CancellationToken first = new CancellationToken();
        CancellationToken next = new CancellationToken();
        var messages = List.of(LlmClient.Message.user("hello"));
        var worker = Executors.newSingleThreadExecutor();
        try {
            var request = worker.submit(() -> CancellationContext.call(first, () -> client.chat(messages, null)));
            assertTrue(requestStarted.await(5, TimeUnit.SECONDS));

            first.cancel();

            ExecutionException failure = assertThrows(ExecutionException.class, () -> request.get(2, TimeUnit.SECONDS));
            assertEquals("cancelled", assertInstanceOf(IOException.class, failure.getCause()).getMessage());
            assertEquals("ok", worker.submit(() ->
                    CancellationContext.call(next, () -> client.chat(messages, null))).get(5, TimeUnit.SECONDS).content());
            next.cancel();
            assertFalse(completedCall.get().isCanceled());
        } finally {
            first.cancel();
            worker.shutdownNow();
        }
    }
}
