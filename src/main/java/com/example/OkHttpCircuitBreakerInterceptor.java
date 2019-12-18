package com.example;

import com.linecorp.armeria.client.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Duration;

public class OkHttpCircuitBreakerInterceptor implements Interceptor {
    private static final Logger log = LoggerFactory.getLogger(OkHttpCircuitBreakerInterceptor.class);
    private static final MediaType MEDIA_TYPE_JSON = MediaType.get("application/json");

    private final CircuitBreaker circuitBreaker;

    public OkHttpCircuitBreakerInterceptor(CircuitBreaker circuitBreaker) {
        this.circuitBreaker = circuitBreaker;
    }

    @Override
    public Response intercept(Chain chain) throws IOException {
        if (circuitBreaker.canRequest()) {
            Request request = chain.request();
            Response response = chain.proceed(request);
            log.info("got response: {} -> {}", request.url(), response.code());
            if (response.isSuccessful() || response.isRedirect()) {
                circuitBreaker.onSuccess();
            } else {
                circuitBreaker.onFailure();
            }
            return response;
        } else {
            Request request = chain.request();
            log.info("Not accessible: {}", request.url());
            return new Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(500)
                    .message("Connection Internally Denied by Circuit Breaker")
                    .body(ResponseBody.create(MEDIA_TYPE_JSON,
                            "{\"message\":\"Connection Internally Denied by Circuit Breaker\"}"))
                    .build();
        }
    }
}
