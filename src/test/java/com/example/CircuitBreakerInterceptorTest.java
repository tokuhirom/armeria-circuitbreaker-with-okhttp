package com.example;

import com.linecorp.armeria.client.circuitbreaker.*;
import io.micrometer.core.instrument.Metrics;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.jetbrains.annotations.NotNull;
import org.junit.*;
import org.slf4j.bridge.SLF4JBridgeHandler;

import java.io.IOException;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;
import java.util.logging.Logger;

public class CircuitBreakerInterceptorTest {
    MockWebServer server;
    private OkHttpClient client;
    private CircuitBreaker circuitBreaker;
    private long rejectedCount;

    @BeforeClass
    public static void init() {
        SLF4JBridgeHandler.install();
        Logger.getLogger(MockWebServer.class.getName()).setLevel(Level.WARNING);
    }

    @Before
    public void setup() throws IOException {
        // テストにつかう mock web server を構築していく。
        server = new MockWebServer();
        final Dispatcher dispatcher = new Dispatcher() {
            @NotNull
            @Override
            public MockResponse dispatch(RecordedRequest request) throws InterruptedException {
                switch (Objects.requireNonNull(request.getPath())) {
                    case "/ok":
                        return new MockResponse().setResponseCode(200);
                    case "/fail":
                        return new MockResponse().setResponseCode(500).setBody("FAIL");
                    case "/fail/10p":
                        if (ThreadLocalRandom.current()
                                .nextInt(1, 10) == 9) {
                            return new MockResponse().setResponseCode(500).setBody("FAIL");
                        } else {
                            return new MockResponse().setResponseCode(200).setBody("OK");
                        }
                    case "/fail/30p":
                        if (ThreadLocalRandom.current()
                                .nextInt(1, 10) <= 3) {
                            return new MockResponse().setResponseCode(500).setBody("FAIL");
                        } else {
                            return new MockResponse().setResponseCode(200).setBody("OK");
                        }
                }
                return new MockResponse().setResponseCode(404);
            }
        };
        server.setDispatcher(dispatcher);
        server.start();

        // クライアント側を作っていく。
        // Micrometer に export できるが、export のタイミングが割と独特かも。
        final MetricCollectingCircuitBreakerListener listener =
                new MetricCollectingCircuitBreakerListener(Metrics.globalRegistry);
        this.circuitBreaker = CircuitBreaker.builder("my-great-cb")
                .listener(listener)
                .listener(new CircuitBreakerListener() { // debug のために色々書いとく。

                    @Override
                    public void onStateChanged(String circuitBreakerName, CircuitState state) throws Exception {
                        System.out.println("State changed: " + state);
                    }

                    @Override
                    public void onEventCountUpdated(String circuitBreakerName, EventCount eventCount) throws Exception {
                        System.out.println(eventCount);
                    }

                    @Override
                    public void onRequestRejected(String circuitBreakerName) throws Exception {
                        rejectedCount++;
                    }
                })
                .build();

        clearCounters();

        client = new OkHttpClient.Builder()
                .callTimeout(Duration.ofSeconds(1))
                .addInterceptor(new OkHttpCircuitBreakerInterceptor(circuitBreaker))
                .build();
    }

    private void clearCounters() {
        rejectedCount = 0;
    }

    @After
    public void teardown() throws IOException {
        server.shutdown();
    }

    @Test
    public void ok() {
        call("/ok");
    }

    @Test
    public void fail10p() {
        callMultiple(1000000, "/fail10p");
        sleep(5);
        callMultiple(1000000, "/fail10p");
        sleep(3);
        callMultiple(1000000, "/fail10p");
    }

    @Test
    public void fail30p() {
        callMultiple(1000000, "/fail30p");
        sleep(5);
        callMultiple(1000000, "/fail30p");
        sleep(3);
        callMultiple(1000000, "/fail30p");
    }

    @Test
    public void fail() throws IOException, InterruptedException {
        diag("Send failure");
        callMultiple(10000, "/fail");

        diag("Wow, failure again");
        callMultiple(10000, "/fail");


        sleep(10);

        callMultiple(1, "/ok");
        callMultiple(10000, "/ok");
        diag("done");
    }

    public void call(String path) {
        String url = server.url(path).toString();

        Request request = new Request.Builder()
                .url(url)
                .build();

        try (Response response = client.newCall(request)
                .execute()) {
            Assert.assertNotNull(response.message());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void callMultiple(int n, String path) {
        diag("Call " + path + " " + n + " times.");
        for (int i = 0; i < n; i++) {
            call(path);
        }
    }

    private void sleep(int i) {
        diag("Sleep " + i + " sec");

        try {
            Thread.sleep(i * 1000);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    private void diag(String message) {
        System.out.println("============> (Rejected:" + rejectedCount + ") \t" + message);
    }
}
