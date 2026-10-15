package com.finsentinel.platform;

import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import java.util.List;
import java.util.concurrent.TimeUnit;

/** Every span the platform finishes, kept in memory (ADR 0020). Spring Boot hands span exporter beans to the SDK. */
@TestConfiguration(proxyBeanMethods = false)
public class TestTracing {

    @Bean
    InMemorySpanExporter spans() {
        return InMemorySpanExporter.create();
    }

    /** Finished spans so far, after flushing the batch processor. */
    public static List<SpanData> finished(SdkTracerProvider provider, InMemorySpanExporter spans) {
        provider.forceFlush().join(10, TimeUnit.SECONDS);
        return spans.getFinishedSpanItems();
    }
}
