package com.microsoft.azure.functions.opentelemetry;

import com.microsoft.azure.functions.internal.spi.middleware.Middleware;
import com.microsoft.azure.functions.internal.spi.middleware.MiddlewareChain;
import com.microsoft.azure.functions.internal.spi.middleware.MiddlewareContext;

import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Scope;


/**
 * OpenTelemetry middleware that creates spans for Azure Functions invocations.
 */
public class OpenTelemetryInvocationMiddleware implements Middleware {

    /**
     * Constructs the middleware and initializes OpenTelemetry.
     */
    public OpenTelemetryInvocationMiddleware() {
        //FunctionsOpenTelemetry.initialize();
    }

    /**
     * Creates a span for the function invocation with tracing context.
     */

    @Override
    public void invoke(MiddlewareContext context, MiddlewareChain chain) throws Exception {
        String spanName = "Invoke";//context.getFunctionName();
        
        // Configure logger for this invocation
        FunctionsOpenTelemetry.setLogger(context.getLogger());
        
        // Create baggage FIRST - before creating any spans
        Baggage baggage = Baggage.current()
            .toBuilder()
            .put("faas.invocation_id", context.getInvocationId())
            .put("faas.name", "test_" + context.getFunctionName())
            .put("test.explicit0", "this-should-work")
            .build();

        // Make baggage current before creating spans
        try (Scope baggageScope = baggage.makeCurrent()) {
            // Now create the span - BaggageSpanProcessor will automatically add baggage as attributes
            Span invocationSpan = FunctionsOpenTelemetry.startSpan(spanName, context.getTraceContext(), SpanKind.INTERNAL);
            invocationSpan.setAttribute("test.explicit1", "this-should-work");
            try (Scope spanScope = invocationSpan.makeCurrent()) {
                // No need to manually set attributes - they're already added by BaggageSpanProcessor!
                invocationSpan.setAttribute("faas.invocation_id", "test_" + context.getInvocationId());
                invocationSpan.setAttribute("faas.name.custom", context.getFunctionName());
                invocationSpan.setAttribute("test.explicit2", "this-should-work");
                // Continue with the middleware chain - all child spans will also get the attributes automatically
                chain.doNext(context);

            } catch (Throwable throwable) {
                // Record exception and set error status
                invocationSpan.recordException(throwable);
                invocationSpan.setStatus(StatusCode.ERROR, throwable.getMessage());
                throw throwable;
            } finally {
                invocationSpan.end();
            }
        }
    }
}
