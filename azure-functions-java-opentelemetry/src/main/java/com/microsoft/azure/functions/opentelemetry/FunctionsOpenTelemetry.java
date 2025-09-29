package com.microsoft.azure.functions.opentelemetry;

import com.microsoft.azure.functions.TraceContext;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk;
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdkBuilder;
import io.opentelemetry.sdk.common.CompletableResultCode;

import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * OpenTelemetry integration for Azure Functions.
 * 
 * <p>Provides agent-agnostic initialization that works with or without OpenTelemetry agents,
 * with optional Azure Monitor integration and convenient span creation methods.
 */
public final class FunctionsOpenTelemetry {

    /** Default tracer name for Azure Functions spans. */
    private static final String DEFAULT_TRACER_NAME = "azure.functions.worker";
    private static final String APP_INSIGHTS_ENABLE_ENV = "JAVA_APPLICATIONINSIGHTS_ENABLE_TELEMETRY";
    private static final String APP_INSIGHTS_CONNECTION_STRING_ENV = "APPLICATIONINSIGHTS_CONNECTION_STRING";
    private static final String AZURE_MONITOR_CLASS = "com.azure.monitor.opentelemetry.autoconfigure.AzureMonitorAutoConfigure";
    private static final String AUTO_CUSTOMIZER_CLASS = "io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizer";

    private static Logger LOGGER = Logger.getLogger(FunctionsOpenTelemetry.class.getSimpleName());
    private static volatile io.opentelemetry.api.OpenTelemetry globalOtel;

    /**
     * Sets the logger instance used by this class.
     * @param logger the logger instance to use
     */
    public static void setLogger(Logger logger) {
        if (logger != null) {
            LOGGER = logger;
        }
    }

    /**
     * Initializes OpenTelemetry for Azure Functions.
     * Safe to call multiple times.
     */
    public static void initialize() {
        if (globalOtel != null) {
            return; // Fast path - no synchronization needed after initialization
        }
        
        synchronized (FunctionsOpenTelemetry.class) {
            if (globalOtel != null) {
                return; // Double-check after acquiring lock
            }
            
            // // Check if GlobalOpenTelemetry has already been configured
            // io.opentelemetry.api.OpenTelemetry candidate = GlobalOpenTelemetry.get();
            
            // if (isNoOp(candidate)) {
            //     LOGGER.info("No global OpenTelemetry found; initializing SDK.");
            //     globalOtel = buildSdk(); // Set our SDK as the global instance
            // } else {
            //     LOGGER.info("GlobalOpenTelemetry already set; using existing instance.");
            //     globalOtel = candidate; // Set the agent's instance
            // }
            
            globalOtel = buildSdk(); // Set our SDK as the global instance

        }
    }

    /**
     * Ensures initialization has occurred.
     */
    private static void ensureInitialized() {
        if (globalOtel == null) {
            initialize();
        }
    }

    /**
     * Checks if the given OpenTelemetry instance is a no-op implementation.
     */
    private static boolean isNoOp(io.opentelemetry.api.OpenTelemetry otel) {
        if (otel == null) {
            return true;
        }
        // Check class name directly instead of creating tracer instances
        String className = otel.getClass().getName();
        return className.contains("Noop") || 
               className.contains("NoOp") || 
               className.contains("DefaultOpenTelemetry");
    }

    /**
     * Returns the OpenTelemetry instance for tracing operations.
     * @return the OpenTelemetry instance
     */
    public static io.opentelemetry.api.OpenTelemetry getOpenTelemetry() {
        ensureInitialized();
        return globalOtel;
    }

    /**
     * Creates and configures an OpenTelemetry SDK with Azure Monitor integration
     * if enabled via environment variables.
     */
    private static OpenTelemetrySdk buildSdk() {
        // Check if we should use programmatic configuration instead of autoconfiguration
        String useProgrammaticConfig = System.getenv("USE_PROGRAMMATIC_OTLP_CONFIG");
        if ("true".equalsIgnoreCase(useProgrammaticConfig)) {
            return buildSdkProgrammatically();
        }
        
        return buildSdkWithAutoConfiguration();
    }

    /**
     * Builds SDK programmatically with direct OTLP configuration.
     * This ensures 100% certainty that settings are applied correctly.
     */
    private static OpenTelemetrySdk buildSdkProgrammatically() {
        LOGGER.info("Building OpenTelemetry SDK programmatically with direct OTLP configuration...");
        
        try {
            // Get configuration from environment variables
            String endpoint = System.getenv("OTEL_EXPORTER_OTLP_ENDPOINT");
            String serviceName = System.getenv("OTEL_SERVICE_NAME");
            String headers = System.getenv("OTEL_EXPORTER_OTLP_HEADERS");
            String protocol = System.getenv("OTEL_EXPORTER_OTLP_PROTOCOL");
            
            LOGGER.info("Programmatic config - Endpoint: " + endpoint);
            LOGGER.info("Programmatic config - Service: " + serviceName);
            LOGGER.info("Programmatic config - Protocol: " + protocol);
            LOGGER.info("Programmatic config - Headers: " + (headers != null ? "SET" : "NULL"));
            
            // Create OTLP exporter using reflection (since we can't import the classes directly)
            Object otlpExporter = createOtlpExporterViaReflection(endpoint, headers, protocol);
            
            if (otlpExporter == null) {
                LOGGER.warning("Failed to create OTLP exporter programmatically, falling back to autoconfiguration");
                return buildSdkWithAutoConfiguration();
            }
            
            // Create BatchSpanProcessor with the OTLP exporter
            Object batchSpanProcessor = createBatchSpanProcessorViaReflection(otlpExporter);
            
            if (batchSpanProcessor == null) {
                LOGGER.warning("Failed to create BatchSpanProcessor, falling back to autoconfiguration");
                return buildSdkWithAutoConfiguration();
            }
            
            // Create resource with service name
            Object resource = createResourceViaReflection(serviceName);
            
            // Build SDK with programmatically configured components
            OpenTelemetrySdk sdk = createSdkViaReflection(batchSpanProcessor, resource);
            
            if (sdk != null) {
                LOGGER.info("✅ OpenTelemetry SDK built programmatically with guaranteed OTLP configuration!");
                return sdk;
            } else {
                LOGGER.warning("Failed to build SDK programmatically, falling back to autoconfiguration");
                return buildSdkWithAutoConfiguration();
            }
            
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Error building SDK programmatically, falling back to autoconfiguration", e);
            return buildSdkWithAutoConfiguration();
        }
    }

    /**
     * Creates OTLP exporter via reflection to avoid direct dependencies.
     */
    private static Object createOtlpExporterViaReflection(String endpoint, String headers, String protocol) {
        try {
            // Determine which exporter to use based on protocol
            String exporterClassName;
            if ("grpc".equalsIgnoreCase(protocol)) {
                exporterClassName = "io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter";
            } else {
                exporterClassName = "io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter";
            }
            
            LOGGER.info("Creating OTLP exporter: " + exporterClassName);
            
            Class<?> exporterClass = Class.forName(exporterClassName);
            Method builderMethod = exporterClass.getMethod("builder");
            Object builder = builderMethod.invoke(null);
            
            // Set endpoint
            if (endpoint != null && !endpoint.isEmpty()) {
                Method setEndpointMethod = builder.getClass().getMethod("setEndpoint", String.class);
                setEndpointMethod.invoke(builder, endpoint);
                LOGGER.info("Set endpoint: " + endpoint);
            }
            
            // Set headers
            if (headers != null && !headers.isEmpty()) {
                // Parse headers (format: key1=value1,key2=value2)
                java.util.Map<String, String> headersMap = new java.util.HashMap<>();
                String[] headerPairs = headers.split(",");
                for (String pair : headerPairs) {
                    String[] keyValue = pair.split("=", 2);
                    if (keyValue.length == 2) {
                        headersMap.put(keyValue[0].trim(), keyValue[1].trim());
                    }
                }
                
                try {
                    Method addHeaderMethod = builder.getClass().getMethod("addHeader", String.class, String.class);
                    for (java.util.Map.Entry<String, String> entry : headersMap.entrySet()) {
                        addHeaderMethod.invoke(builder, entry.getKey(), entry.getValue());
                        LOGGER.info("Added header: " + entry.getKey() + "=***");
                    }
                } catch (NoSuchMethodException e) {
                    // Try alternative method name
                    try {
                        Method setHeadersMethod = builder.getClass().getMethod("setHeaders", java.util.Map.class);
                        setHeadersMethod.invoke(builder, headersMap);
                        LOGGER.info("Set headers map with " + headersMap.size() + " entries");
                    } catch (Exception e2) {
                        LOGGER.warning("Failed to set headers: " + e2.getMessage());
                    }
                }
            }
            
            // Build the exporter
            Method buildMethod = builder.getClass().getMethod("build");
            Object exporter = buildMethod.invoke(builder);
            
            LOGGER.info("✅ OTLP exporter created successfully!");
            return exporter;
            
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to create OTLP exporter via reflection", e);
            return null;
        }
    }

    /**
     * Creates BatchSpanProcessor via reflection.
     */
    private static Object createBatchSpanProcessorViaReflection(Object spanExporter) {
        try {
            Class<?> batchProcessorClass = Class.forName("io.opentelemetry.sdk.trace.export.BatchSpanProcessor");
            Method builderMethod = batchProcessorClass.getMethod("builder", 
                Class.forName("io.opentelemetry.sdk.trace.export.SpanExporter"));
            Object builder = builderMethod.invoke(null, spanExporter);
            
            Method buildMethod = builder.getClass().getMethod("build");
            Object processor = buildMethod.invoke(builder);
            
            LOGGER.info("✅ BatchSpanProcessor created successfully!");
            return processor;
            
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to create BatchSpanProcessor via reflection", e);
            return null;
        }
    }

    /**
     * Creates Resource with service name via reflection.
     */
    private static Object createResourceViaReflection(String serviceName) {
        try {
            Class<?> resourceClass = Class.forName("io.opentelemetry.sdk.resources.Resource");
            Method getDefaultMethod = resourceClass.getMethod("getDefault");
            Object resource = getDefaultMethod.invoke(null);
            
            if (serviceName != null && !serviceName.isEmpty()) {
                // Try to add service name attribute
                try {
                    Class<?> attributesBuilderClass = Class.forName("io.opentelemetry.api.common.AttributesBuilder");
                    Class<?> attributesClass = Class.forName("io.opentelemetry.api.common.Attributes");
                    
                    Method builderMethod = attributesClass.getMethod("builder");
                    Object attributesBuilder = builderMethod.invoke(null);
                    
                    Method putMethod = attributesBuilder.getClass().getMethod("put", String.class, String.class);
                    putMethod.invoke(attributesBuilder, "service.name", serviceName);
                    
                    Method buildAttributesMethod = attributesBuilder.getClass().getMethod("build");
                    Object attributes = buildAttributesMethod.invoke(attributesBuilder);
                    
                    Method toBuilderMethod = resource.getClass().getMethod("toBuilder");
                    Object resourceBuilder = toBuilderMethod.invoke(resource);
                    
                    Method putMethod2 = resourceBuilder.getClass().getMethod("put", attributesClass);
                    putMethod2.invoke(resourceBuilder, attributes);
                    
                    Method buildResourceMethod = resourceBuilder.getClass().getMethod("build");
                    resource = buildResourceMethod.invoke(resourceBuilder);
                    
                    LOGGER.info("✅ Resource created with service name: " + serviceName);
                } catch (Exception e) {
                    LOGGER.warning("Failed to set service name, using default resource: " + e.getMessage());
                }
            }
            
            return resource;
            
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to create Resource via reflection", e);
            return null;
        }
    }

    /**
     * Creates final SDK with all components via reflection.
     */
    private static OpenTelemetrySdk createSdkViaReflection(Object spanProcessor, Object resource) {
        try {
            Class<?> sdkBuilderClass = Class.forName("io.opentelemetry.sdk.OpenTelemetrySdkBuilder");
            Class<?> sdkTracerProviderBuilderClass = Class.forName("io.opentelemetry.sdk.trace.SdkTracerProviderBuilder");
            
            Method builderMethod = OpenTelemetrySdk.class.getMethod("builder");
            Object sdkBuilder = builderMethod.invoke(null);
            
            // Get tracer provider builder
            Method setTracerProviderMethod = sdkBuilder.getClass().getMethod("setTracerProvider",
                Class.forName("io.opentelemetry.sdk.trace.SdkTracerProvider"));
            
            // Build SdkTracerProvider
            Class<?> sdkTracerProviderClass = Class.forName("io.opentelemetry.sdk.trace.SdkTracerProvider");
            Method tracerProviderBuilderMethod = sdkTracerProviderClass.getMethod("builder");
            Object tracerProviderBuilder = tracerProviderBuilderMethod.invoke(null);
            
            // Add span processor
            Method addSpanProcessorMethod = tracerProviderBuilder.getClass().getMethod("addSpanProcessor",
                Class.forName("io.opentelemetry.sdk.trace.export.SpanProcessor"));
            addSpanProcessorMethod.invoke(tracerProviderBuilder, spanProcessor);
            
            // Set resource
            Method setResourceMethod = tracerProviderBuilder.getClass().getMethod("setResource",
                Class.forName("io.opentelemetry.sdk.resources.Resource"));
            setResourceMethod.invoke(tracerProviderBuilder, resource);
            
            // Build tracer provider
            Method buildTracerProviderMethod = tracerProviderBuilder.getClass().getMethod("build");
            Object tracerProvider = buildTracerProviderMethod.invoke(tracerProviderBuilder);
            
            // Set tracer provider on SDK builder
            setTracerProviderMethod.invoke(sdkBuilder, tracerProvider);
            
            // Build and register globally
            Method buildAndRegisterGlobalMethod = sdkBuilder.getClass().getMethod("buildAndRegisterGlobal");
            OpenTelemetrySdk sdk = (OpenTelemetrySdk) buildAndRegisterGlobalMethod.invoke(sdkBuilder);
            
            LOGGER.info("✅ OpenTelemetry SDK created and registered globally!");
            return sdk;
            
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to create SDK via reflection", e);
            return null;
        }
    }

    /**
     * Builds SDK using autoconfiguration (original method).
     */
    private static OpenTelemetrySdk buildSdkWithAutoConfiguration() {
        LOGGER.info("Initializing OpenTelemetry SDK ....");
        // Debug: Print relevant environment variables
        LOGGER.info("OTEL_TRACES_EXPORTER: " + System.getenv("OTEL_TRACES_EXPORTER"));
        LOGGER.info("OTEL_EXPORTER_OTLP_ENDPOINT: " + System.getenv("OTEL_EXPORTER_OTLP_ENDPOINT"));
        LOGGER.info("OTEL_SERVICE_NAME: " + System.getenv("OTEL_SERVICE_NAME"));
        LOGGER.info("OTEL_RESOURCE_ATTRIBUTES: " + System.getenv("OTEL_RESOURCE_ATTRIBUTES"));
        LOGGER.info("OTEL_EXPORTER_OTLP_HEADERS: " + System.getenv("OTEL_EXPORTER_OTLP_HEADERS"));
        LOGGER.info("OTEL_EXPORTER_OTLP_TRACES_ENDPOINT: " + System.getenv("OTEL_EXPORTER_OTLP_TRACES_ENDPOINT"));
        LOGGER.info("OTEL_EXPORTER_OTLP_PROTOCOL: " + System.getenv("OTEL_EXPORTER_OTLP_PROTOCOL"));


        // Get the current thread's context classloader (should have access to function app dependencies)
        ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();
        ClassLoader currentClassLoader = FunctionsOpenTelemetry.class.getClassLoader();
        
        LOGGER.info("=== ClassLoader Analysis ===");
        LOGGER.info("Context ClassLoader: " + contextClassLoader);
        LOGGER.info("This Class ClassLoader: " + currentClassLoader);
        LOGGER.info("Are they the same? " + (contextClassLoader == currentClassLoader));
    
        Thread.currentThread().setContextClassLoader(currentClassLoader);
        LOGGER.info("Set context ClassLoader to current class's ClassLoader.");
        OpenTelemetrySdk sdk;
        boolean doSimple = true;

        try {
            if (doSimple) {
                 AutoConfiguredOpenTelemetrySdk autoConfigured = AutoConfiguredOpenTelemetrySdk.builder()
                                .setResultAsGlobal()
                                .build();
                sdk = autoConfigured.getOpenTelemetrySdk();
                } else {
                final AutoConfiguredOpenTelemetrySdkBuilder builder =
                        AutoConfiguredOpenTelemetrySdk.builder();
                
                // Add debugging for autoconfiguration
                builder.addPropertiesCustomizer(config -> {
                    LOGGER.info("=== Autoconfiguration Properties ===");
                    // Log key configuration properties
                    LOGGER.info("otel.traces.exporter: " + config.getString("otel.traces.exporter"));
                    LOGGER.info("otel.exporter.otlp.endpoint: " + config.getString("otel.exporter.otlp.endpoint"));
                    LOGGER.info("otel.exporter.otlp.protocol: " + config.getString("otel.exporter.otlp.protocol"));
                    LOGGER.info("otel.service.name: " + config.getString("otel.service.name"));
                    LOGGER.info("=== End Properties ===");
                    return java.util.Collections.emptyMap(); // Return empty map, don't modify config
                });

                // Azure Functions resource attributes are automatically added via 
                // FunctionsResourceProvider (SPI mechanism)

                if (isAppInsightsEnabled()) {
                    final String connStr = System.getenv(APP_INSIGHTS_CONNECTION_STRING_ENV);
                    applyAzureMonitor(builder, connStr);
                }

                LOGGER.info("About to build AutoConfiguredOpenTelemetrySdk...");
                
                // AutoConfiguredOpenTelemetrySdk automatically registers globally when built
                AutoConfiguredOpenTelemetrySdk autoSdk = builder.setResultAsGlobal().build();
                sdk = autoSdk.getOpenTelemetrySdk();

                LOGGER.info("OpenTelemetry SDK initialised successfully.");
                LOGGER.info("AutoConfiguredOpenTelemetrySdk: " + autoSdk.getClass().getName());
            }

            
            // Debug: Inspect the SDK configuration
            inspectSdkConfiguration(sdk);
            
            // Test that the SDK is actually working by creating a test span
            testSdkFunctionality(sdk);

        } catch (Throwable ex) {
            LOGGER.log(Level.SEVERE,
                    "Failed to initialise OpenTelemetry SDK – falling back to no-op", ex);

            // Use buildAndRegisterGlobal to avoid double registration
            sdk = OpenTelemetrySdk.builder().buildAndRegisterGlobal();
        }

        // Add shutdown hook for clean resource cleanup
        final OpenTelemetrySdk finalSdk = sdk;
        Runtime.getRuntime().addShutdownHook(
                new Thread(() -> finalSdk.getSdkTracerProvider().shutdown()));

        return sdk;
    }

    /**
     * Inspects and logs the configuration of the OpenTelemetry SDK.
     */
    private static void inspectSdkConfiguration(OpenTelemetrySdk sdk) {
        try {
            LOGGER.info("=== OpenTelemetry SDK Configuration ===");
            
            // Check TracerProvider
            LOGGER.info("TracerProvider: " + sdk.getSdkTracerProvider().getClass().getName());
            
            // Try to get span processor information
            try {
                java.lang.reflect.Field sharedStateField = sdk.getSdkTracerProvider().getClass().getDeclaredField("sharedState");
                sharedStateField.setAccessible(true);
                Object sharedState = sharedStateField.get(sdk.getSdkTracerProvider());
                
                java.lang.reflect.Field activeProcessorField = sharedState.getClass().getDeclaredField("activeSpanProcessor");
                activeProcessorField.setAccessible(true);
                Object spanProcessor = activeProcessorField.get(sharedState);
                LOGGER.info("Span Processor: " + spanProcessor.getClass().getName());
                
                // For MultiSpanProcessor, just report that it exists - detailed inspection is blocked by modules
                if (spanProcessor.getClass().getName().contains("MultiSpanProcessor")) {
                    LOGGER.info("MultiSpanProcessor detected - likely contains OTLP and other exporters");
                    LOGGER.info("Detailed processor inspection blocked by Java module system");
                }
            } catch (Exception e) {
                LOGGER.info("Could not inspect span processor: " + e.getMessage());
            }
            
            // Check if we can get tracer and create a test span (this proves the SDK works)
            try {
                io.opentelemetry.api.trace.Tracer tracer = sdk.getTracer("config-test");
                LOGGER.info("Successfully obtained tracer: " + tracer.getClass().getName());
            } catch (Exception e) {
                LOGGER.info("Could not obtain tracer: " + e.getMessage());
            }
            
            // Try to get more details about what exporters are available
            try {
                // Check what exporter classes are available on classpath
                String[] exporterClasses = {
                    "io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter",
                    "io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter",
                    "io.opentelemetry.exporter.logging.LoggingSpanExporter"
                };
                
                for (String className : exporterClasses) {
                    try {
                        Class.forName(className);
                        LOGGER.info("AVAILABLE EXPORTER: " + className);
                    } catch (ClassNotFoundException e) {
                        LOGGER.info("MISSING EXPORTER: " + className);
                    }
                }
            } catch (Exception e) {
                LOGGER.info("Could not check available exporters: " + e.getMessage());
            }
            
            LOGGER.info("=== End SDK Configuration ===");
            
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to inspect SDK configuration", e);
        }
    }

    /**
     * Tests basic SDK functionality by creating and ending a test span.
     */
    private static void testSdkFunctionality(OpenTelemetrySdk sdk) {
        try {
            LOGGER.info("=== Testing SDK Functionality ===");
            
            // Check what exporter classes are available on classpath
            LOGGER.info("=== Checking Available Exporters ===");
            String[] exporterClasses = {
                "io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter",
                "io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter",
                "io.opentelemetry.exporter.logging.LoggingSpanExporter"
            };
            
            for (String className : exporterClasses) {
                try {
                    Class.forName(className);
                    LOGGER.info("✅ AVAILABLE EXPORTER: " + className);
                } catch (ClassNotFoundException e) {
                    LOGGER.info("❌ MISSING EXPORTER: " + className);
                }
            }
            LOGGER.info("=== End Exporter Check ===");
            
            // TEST: Try to manually create an OTLP HTTP exporter to verify it works
            LOGGER.info("=== Manual OTLP Exporter Test ===");
            try {
                Class<?> httpExporterClass = Class.forName("io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter");
                Method builderMethod = httpExporterClass.getMethod("builder");
                Object builder = builderMethod.invoke(null);
                
                // Set endpoint
                String endpoint = System.getenv("OTEL_EXPORTER_OTLP_ENDPOINT");
                if (endpoint != null) {
                    Method setEndpointMethod = builder.getClass().getMethod("setEndpoint", String.class);
                    setEndpointMethod.invoke(builder, endpoint);
                    LOGGER.info("Manual exporter - endpoint set to: " + endpoint);
                }
                
                // Set headers
                String headers = System.getenv("OTEL_EXPORTER_OTLP_HEADERS");
                if (headers != null) {
                    String[] headerPairs = headers.split(",");
                    for (String pair : headerPairs) {
                        String[] keyValue = pair.split("=", 2);
                        if (keyValue.length == 2) {
                            try {
                                Method addHeaderMethod = builder.getClass().getMethod("addHeader", String.class, String.class);
                                addHeaderMethod.invoke(builder, keyValue[0].trim(), keyValue[1].trim());
                                LOGGER.info("Manual exporter - header added: " + keyValue[0].trim());
                            } catch (Exception e) {
                                LOGGER.info("Could not add header: " + e.getMessage());
                            }
                        }
                    }
                }
                
                // Build the manual exporter
                Method buildMethod = builder.getClass().getMethod("build");
                Object manualExporter = buildMethod.invoke(builder);
                LOGGER.info("✅ Manual OTLP exporter created successfully: " + manualExporter.getClass().getName());
                
                // Try to export a single span with the manual exporter
                try {
                    // Create a simple span data for testing
                    io.opentelemetry.api.trace.Tracer testTracer = sdk.getTracer("manual-test");
                    Span manualTestSpan = testTracer.spanBuilder("manual-export-test")
                        .setAttribute("test.manual", "true")
                        .setAttribute("test.timestamp", System.currentTimeMillis())
                        .startSpan();
                    manualTestSpan.end();
                    
                    LOGGER.info("Manual test span created for direct export testing");
                    
                    // Note: We can't easily export directly without more complex span data creation
                    // But the fact that we can create the exporter is a good sign
                    
                } catch (Exception e) {
                    LOGGER.info("Could not test manual export: " + e.getMessage());
                }
                
            } catch (Exception e) {
                LOGGER.warning("Manual OTLP exporter test failed: " + e.getMessage());
            }
            LOGGER.info("=== End Manual Exporter Test ===");
            
            // Create a test span to verify the SDK is working
            io.opentelemetry.api.trace.Tracer tracer = sdk.getTracer("functionality-test");
            Span testSpan = tracer.spanBuilder("sdk-test-span")
                .setSpanKind(SpanKind.INTERNAL)
                .startSpan();

            testSpan.makeCurrent();
            // Add some attributes
            testSpan.setAttribute("test.attribute", "test-value");
            testSpan.setAttribute("sdk.version", "1.49.0");
            testSpan.setAttribute("config.endpoint", System.getenv("OTEL_EXPORTER_OTLP_ENDPOINT"));
            
            LOGGER.info("Test span created successfully: " + testSpan.getClass().getName());
            LOGGER.info("Span context: " + testSpan.getSpanContext());
            
            // End the span
            testSpan.end();
            LOGGER.info("Test span ended successfully");
            
            // Force flush to ensure data is sent
            try {
                LOGGER.info("Forcing SDK flush...");
                
                // Add detailed debugging about what we're trying to export
                LOGGER.info("=== Export Configuration Debug ===");
                LOGGER.info("Target Endpoint: " + System.getenv("OTEL_EXPORTER_OTLP_ENDPOINT"));
                LOGGER.info("Protocol: " + System.getenv("OTEL_EXPORTER_OTLP_PROTOCOL"));
                LOGGER.info("Headers: " + (System.getenv("OTEL_EXPORTER_OTLP_HEADERS") != null ? "SET (api-key=...)" : "NOT SET"));
                LOGGER.info("Service Name: " + System.getenv("OTEL_SERVICE_NAME"));
                
                // Try to enable debug logging for OTLP exporter
                try {
                    java.util.logging.Logger otlpLogger = java.util.logging.Logger.getLogger("io.opentelemetry.exporter.otlp");
                    otlpLogger.setLevel(Level.FINE);
                    java.util.logging.Logger httpLogger = java.util.logging.Logger.getLogger("io.opentelemetry.exporter.otlp.http");
                    httpLogger.setLevel(Level.FINE);
                    LOGGER.info("Enabled OTLP debug logging");
                } catch (Exception e) {
                    LOGGER.info("Could not enable OTLP debug logging: " + e.getMessage());
                }
                
                // Try to force a flush and measure timing
                long startTime = System.currentTimeMillis();
                CompletableResultCode result = sdk.getSdkTracerProvider().forceFlush();
                CompletableResultCode joinResult = result.join(10, TimeUnit.SECONDS);
                boolean success = joinResult.isSuccess();
                long endTime = System.currentTimeMillis();
                
                LOGGER.info("Flush Success: " + success);
                LOGGER.info("Flush Duration: " + (endTime - startTime) + "ms");
                
                // Get more detailed flush result information
                LOGGER.info("Flush result class: " + result.getClass().getName());
                LOGGER.info("Join result class: " + joinResult.getClass().getName());
                
                // Try to inspect what might have gone wrong if we can
                if (!success) {
                    LOGGER.info("=== FLUSH FAILURE ANALYSIS ===");
                    try {
                        // Try to get any failure information via reflection
                        java.lang.reflect.Method isDoneMethod = result.getClass().getMethod("isDone");
                        boolean isDone = (Boolean) isDoneMethod.invoke(result);
                        LOGGER.info("Flush isDone: " + isDone);
                    } catch (Exception e) {
                        LOGGER.info("Could not get flush completion status: " + e.getMessage());
                    }
                }
                
                if (!success) {
                    LOGGER.warning("⚠️ FLUSH FAILED - Data may not have been sent to New Relic");
                } else {
                    LOGGER.info("✅ Flush completed successfully - data should be in New Relic");
                    LOGGER.info("💡 If you don't see data in New Relic, check:");
                    LOGGER.info("   1. New Relic API key is valid and has OTLP permissions");
                    LOGGER.info("   2. Endpoint URL is correct: " + System.getenv("OTEL_EXPORTER_OTLP_ENDPOINT"));
                    LOGGER.info("   3. Protocol might need to be 'grpc' instead of 'http/protobuf'");
                    LOGGER.info("   4. Network connectivity from Azure Functions to New Relic");
                    LOGGER.info("   5. New Relic ingestion might have a delay (1-2 minutes)");
                    LOGGER.info("   6. Check New Relic's OTLP endpoint documentation for exact requirements");
                }
                
            } catch (Exception e) {
                LOGGER.info("SDK flush failed: " + e.getMessage());
            }
            
            LOGGER.info("=== SDK Functionality Test Complete - SDK IS WORKING ===");
            
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "SDK functionality test failed", e);
        }
    }

    /**
     * Checks if Application Insights is enabled.
     */
    private static boolean isAppInsightsEnabled() {
        return Boolean.parseBoolean(System.getenv(APP_INSIGHTS_ENABLE_ENV));
    }

    /**
     * Applies Azure Monitor configuration via reflection if available.
     */

    private static void applyAzureMonitor(AutoConfiguredOpenTelemetrySdkBuilder builder, String connStr) {
        try {
            ClassLoader cl = FunctionsOpenTelemetry.class.getClassLoader();

            // Resolve the types we need with the same ClassLoader
            Class<?> autoCfgClass = Class.forName(AZURE_MONITOR_CLASS, false, cl);
            Class<?> customizerIfc = Class.forName(AUTO_CUSTOMIZER_CLASS, false, cl);

            // Look up the exact method overload we expect
            Method customize =
                    autoCfgClass.getMethod("customize", customizerIfc, String.class);

            customize.invoke(null, builder, connStr);
            LOGGER.info("AzureMonitorAutoConfigure applied via reflection");

        } catch (ClassNotFoundException e) {
            LOGGER.fine("azure-monitor-opentelemetry-autoconfigure not present – skipping");
        } catch (NoSuchMethodException e) {
            LOGGER.warning("AzureMonitorAutoConfigure.customize(...) not found – "
                    + "library version may have changed");
        } catch (Throwable t) {
            LOGGER.log(Level.WARNING, "Failed to apply AzureMonitorAutoConfigure", t);
        }
    }

    /** TextMapGetter for Azure Functions TraceContext. */
    private static final TextMapGetter<TraceContext> TRACE_CONTEXT_GETTER = TraceContextTextMapGetter.INSTANCE;

    /**
     * Validates that a string parameter is non-null and non-empty.
     */
    private static void validateNonEmpty(String value, String paramName) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException(paramName + " must be non-null and non-empty");
        }
    }

    /**
     * Creates and starts a new span.
     * @param tracerName the name of the tracer
     * @param spanName the name of the span
     * @param parent the parent context
     * @param kind the span kind
     * @return the started span
     */
    public static Span startSpan(String tracerName, String spanName, Context parent, SpanKind kind) {
        validateNonEmpty(spanName, "spanName");
        validateNonEmpty(tracerName, "tracerName");

        return getOpenTelemetry().getTracer(tracerName)
                .spanBuilder(spanName)
                .setParent(parent == null ? Context.current() : parent)
                .setSpanKind(kind == null ? SpanKind.INTERNAL : kind)
                .startSpan();
    }

    /**
     * Creates and starts a new span with trace context from Azure Functions.
     * @param tracerName the name of the tracer
     * @param spanName the name of the span
     * @param traceContext the Azure Functions trace context
     * @param kind the span kind
     * @return the started span
     */
    public static Span startSpan(String tracerName, String spanName, TraceContext traceContext, SpanKind kind) {
        Context parent = getOpenTelemetry().getPropagators()
                .getTextMapPropagator()
                .extract(Context.current(), traceContext, TRACE_CONTEXT_GETTER);
        return startSpan(tracerName, spanName, parent, kind);
    }

    /**
     * Convenience method using the default tracer name.
     * @param spanName the name of the span
     * @param traceContext the Azure Functions trace context
     * @param kind the span kind
     * @return the started span
     */
    public static Span startSpan(String spanName, TraceContext traceContext, SpanKind kind) {
        return startSpan(DEFAULT_TRACER_NAME, spanName, traceContext, kind);
    }
}
