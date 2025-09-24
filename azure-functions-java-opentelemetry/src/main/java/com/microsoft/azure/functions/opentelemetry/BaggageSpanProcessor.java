package com.microsoft.azure.functions.opentelemetry;

import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SpanProcessor;

/**
 * SpanProcessor that automatically adds baggage entries as span attributes.
 * This ensures that all spans created within a baggage context automatically
 * inherit the baggage values as attributes.
 */
public class BaggageSpanProcessor implements SpanProcessor {

    @Override
    public void onStart(Context parentContext, ReadWriteSpan span) {
        Baggage baggage = Baggage.fromContext(parentContext);
        baggage.forEach((key, baggageEntry) -> {
            span.setAttribute(key, baggageEntry.getValue());
        });
    }

    @Override
    public boolean isStartRequired() {
        return true;
    }

    @Override
    public void onEnd(ReadableSpan span) {
        // No action needed on span end
    }

    @Override
    public boolean isEndRequired() {
        return false;
    }
}