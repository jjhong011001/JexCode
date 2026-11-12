package com.jexcode.web;

import com.jexcode.runtime.CancellationContext;
import com.jexcode.runtime.CancellationToken;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebFetcherTest {

    private MockWebServer server;

    @BeforeEach
    void setup() throws IOException {
        server = new MockWebServer();
        server.start();
    }

    @AfterEach
    void shutdown() throws IOException {
        server.shutdown();
    }

    @Test
    void fetchesSuccessfulHtml() throws IOException {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "text/html; charset=utf-8")
                .setBody("<html><body><article><h1>Hello</h1></article></body></html>"));

        WebFetcher fetcher = localFetcher(WebFetcher.DEFAULT_MAX_BYTES, new OkHttpClient());
        WebFetcher.RawResponse raw = fetcher.fetch(server.url("/").toString());

        assertTrue(raw.body().contains("Hello"));
        assertFalse(raw.truncated());
        assertNotNull(raw.contentType());
    }

    @Test
    void truncatesBodyOverMaxBytes() throws IOException {
        StringBuilder big = new StringBuilder("<html><body>");
        // 10KB 正文
        for (int i = 0; i < 10_000; i++) big.append("a");
        big.append("</body></html>");
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "text/html; charset=utf-8")
                .setBody(big.toString()));

        // 设置 1KB 上限
        WebFetcher fetcher = localFetcher(1024, new OkHttpClient());
        WebFetcher.RawResponse raw = fetcher.fetch(server.url("/").toString());

        assertTrue(raw.truncated(), "超过 maxBytes 应标记为 truncated");
        assertTrue(raw.body().length() <= 1024);
    }

    @Test
    void httpErrorThrows() {
        server.enqueue(new MockResponse().setResponseCode(404));
        WebFetcher fetcher = localFetcher(WebFetcher.DEFAULT_MAX_BYTES, new OkHttpClient());
        IOException ex = assertThrows(IOException.class,
                () -> fetcher.fetch(server.url("/missing").toString()));
        assertTrue(ex.getMessage().contains("404"));
    }

    @Test
    void timeoutThrows() {
        // throttleBody 模拟服务器极慢；配合 1 秒读超时触发 timeout
        server.enqueue(new MockResponse()
                .setBody("partial-")
                .throttleBody(1, 5, TimeUnit.SECONDS));

        OkHttpClient client = new OkHttpClient.Builder()
                .readTimeout(1, TimeUnit.SECONDS)
                .callTimeout(2, TimeUnit.SECONDS)
                .build();
        WebFetcher fetcher = localFetcher(WebFetcher.DEFAULT_MAX_BYTES, client);

        assertThrows(IOException.class,
                () -> fetcher.fetch(server.url("/slow").toString()));
    }

    @Test
    void respectsCharsetFromContentType() throws IOException {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "text/html; charset=utf-8")
                .setBody("<html><body>中文测试</body></html>"));
        WebFetcher fetcher = localFetcher(WebFetcher.DEFAULT_MAX_BYTES, new OkHttpClient());
        WebFetcher.RawResponse raw = fetcher.fetch(server.url("/").toString());
        assertTrue(raw.body().contains("中文测试"));
        assertEquals("UTF-8", raw.charset());
    }

    @Test
    void cancelsAnInFlightFetch() throws Exception {
        server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
        WebFetcher fetcher = localFetcher(WebFetcher.DEFAULT_MAX_BYTES, new OkHttpClient());
        CancellationToken token = new CancellationToken();
        var worker = Executors.newSingleThreadExecutor();
        try {
            var fetch = worker.submit(() ->
                    CancellationContext.call(token, () -> fetcher.fetch(server.url("/slow").toString())));
            assertNotNull(server.takeRequest(5, TimeUnit.SECONDS));

            token.cancel();

            ExecutionException error = assertThrows(ExecutionException.class, () -> fetch.get(2, TimeUnit.SECONDS));
            assertTrue(error.getCause() instanceof IOException);
        } finally {
            token.cancel();
            worker.shutdownNow();
        }
    }

    private WebFetcher localFetcher(int maxBytes, OkHttpClient client) {
        String serverOrigin = server.url("/").toString();
        NetworkPolicy policy = new NetworkPolicy() {
            @Override
            public String checkUrl(String url) {
                return url.startsWith(serverOrigin) ? null : super.checkUrl(url);
            }
        };
        return new WebFetcher(maxBytes, client, policy);
    }
}
