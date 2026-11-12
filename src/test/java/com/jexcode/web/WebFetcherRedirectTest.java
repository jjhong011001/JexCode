package com.jexcode.web;

import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class WebFetcherRedirectTest {
    private static final String PUBLIC_URL = "https://203.0.113.10";

    @Test
    void checksEachRedirectBeforeMakingTheNextRequest() {
        List<String> requests = new ArrayList<>();
        WebFetcher fetcher = fetcher(chain -> {
            Request request = chain.request();
            String path = request.url().encodedPath();
            requests.add(request.url().toString());
            return redirect(request, "/start".equals(path) ? "/middle" : "http://127.0.0.1/private");
        });

        IOException error = assertThrows(IOException.class, () -> fetcher.fetch(PUBLIC_URL + "/start"));

        assertTrue(error.getMessage().contains("禁止访问环回地址"), error.getMessage());
        assertEquals(List.of(PUBLIC_URL + "/start", PUBLIC_URL + "/middle"), requests);
    }

    @Test
    void followsPublicRelativeRedirectAndReturnsFinalUrl() throws IOException {
        WebFetcher fetcher = fetcher(chain -> {
            Request request = chain.request();
            if ("/start".equals(request.url().encodedPath())) {
                return redirect(request, "/docs/article");
            }
            return response(request, 200).body(ResponseBody.create("article", null)).build();
        });

        WebFetcher.RawResponse response = fetcher.fetch(PUBLIC_URL + "/start");

        assertEquals(PUBLIC_URL + "/docs/article", response.url());
        assertEquals("article", response.body());
    }

    @Test
    void redirectLoopFailsWithinTheHopLimit() {
        AtomicInteger requests = new AtomicInteger();
        WebFetcher fetcher = fetcher(chain -> {
            assertTrue(requests.incrementAndGet() <= 21, "redirect limit was exceeded");
            return redirect(chain.request(), "/loop");
        });

        IOException error = assertThrows(IOException.class, () -> fetcher.fetch(PUBLIC_URL + "/loop"));

        assertTrue(error.getMessage().contains("重定向次数超出上限"), error.getMessage());
        assertEquals(21, requests.get());
    }

    private static WebFetcher fetcher(Interceptor interceptor) {
        OkHttpClient client = new OkHttpClient.Builder().addInterceptor(interceptor).build();
        return new WebFetcher(WebFetcher.DEFAULT_MAX_BYTES, client);
    }

    private static Response redirect(Request request, String location) {
        return response(request, 302).header("Location", location).build();
    }

    private static Response.Builder response(Request request, int code) {
        return new Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(code).message("test").body(ResponseBody.create("", null));
    }
}
