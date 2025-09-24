package com.microsoft.azure.functions.opentelemetry;

import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizer;
import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizerProvider;

/**
 * Provides automatic registration of the BaggageSpanProcessor.
 * This class is discovered via Java SPI mechanism.
 */
public class FunctionsSpanProcessorCustomizer implements AutoConfigurationCustomizerProvider {

    @Override
    public void customize(AutoConfigurationCustomizer autoConfiguration) {
        autoConfiguration.addTracerProviderCustomizer(
            (tracerProviderBuilder, configProperties) -> {
                tracerProviderBuilder.addSpanProcessor(new BaggageSpanProcessor());
                return tracerProviderBuilder;
            }
        );
    }
}