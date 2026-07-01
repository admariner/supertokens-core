/*
 *    Copyright (c) 2025, VRAI Labs and/or its affiliates. All rights reserved.
 *
 *    This software is licensed under the Apache License, Version 2.0 (the
 *    "License") as published by the Apache Software Foundation.
 *
 *    You may not use this file except in compliance with the License. You may
 *    obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *    WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *    License for the specific language governing permissions and limitations
 *    under the License.
 */

package io.supertokens.telemetry;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.logs.LogRecordBuilder;
import io.opentelemetry.api.logs.Severity;
import io.opentelemetry.api.trace.TracerProvider;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.exporter.otlp.logs.OtlpGrpcLogRecordExporter;
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.logs.SdkLoggerProvider;
import io.opentelemetry.sdk.logs.export.BatchLogRecordProcessor;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.supertokens.Main;
import io.supertokens.ResourceDistributor;
import io.supertokens.config.Config;
import io.supertokens.output.Logging;
import io.supertokens.pluginInterface.multitenancy.TenantIdentifier;
import io.supertokens.pluginInterface.multitenancy.exceptions.TenantOrAppNotFoundException;
import io.supertokens.pluginInterface.opentelemetry.OtelProvider;
import org.jetbrains.annotations.TestOnly;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import static io.opentelemetry.semconv.ServiceAttributes.SERVICE_NAME;

public class TelemetryProvider extends ResourceDistributor.SingletonResource implements OtelProvider {

    private final OpenTelemetry openTelemetry;

    public static TelemetryProvider getInstance(Main main) {
        TelemetryProvider instance = null;
        try {
            instance = (TelemetryProvider) main.getResourceDistributor()
                    .getResource(TenantIdentifier.BASE_TENANT, RESOURCE_ID);
        } catch (TenantOrAppNotFoundException ignored) {
        }
        return instance;
    }

    public static void initialize(Main main) {
        main.getResourceDistributor()
                .setResource(TenantIdentifier.BASE_TENANT, RESOURCE_ID, new TelemetryProvider(main));
    }

    @Override
    public void createLogEvent(TenantIdentifier tenantIdentifier, String logMessage,
                               String logLevel) {
        createLogEvent(tenantIdentifier, logMessage, logLevel, Map.of());
    }

    @Override
    public void createLogEvent(TenantIdentifier tenantIdentifier, String logMessage,
                               String logLevel, Map<String, String> additionalAttributes) {
        if (openTelemetry == null) {
            return; // no telemetry provider available
        }

        // Emit through the LOGS pipeline (LoggerProvider), not the tracer. Log lines
        // are records, not sampled traces -- they carry a severity and are gated by
        // log level upstream in Logging.java, so they must not ride the (samplable,
        // per-line) span path. getLogsBridge() resolves to the built-in
        // SdkLoggerProvider (BatchLogRecordProcessor -> OTLP), or under --javaagent to
        // the agent's logger provider; both export OTLP logs to the collector.
        LogRecordBuilder record = openTelemetry.getLogsBridge()
                .get("core-logger")
                .logRecordBuilder()
                .setTimestamp(System.currentTimeMillis(), TimeUnit.MILLISECONDS)
                .setSeverity(toSeverity(logLevel))   // Axiom highlights by severity
                .setSeverityText(logLevel)
                .setBody(logMessage)
                .setAttribute(AttributeKey.stringKey("tenant.connectionUriDomain"),
                        tenantIdentifier.getConnectionUriDomain())
                .setAttribute(AttributeKey.stringKey("tenant.appId"), tenantIdentifier.getAppId())
                .setAttribute(AttributeKey.stringKey("tenant.tenantId"), tenantIdentifier.getTenantId());

        if (additionalAttributes != null) {
            for (Map.Entry<String, String> attribute : additionalAttributes.entrySet()) {
                record.setAttribute(AttributeKey.stringKey(attribute.getKey()), attribute.getValue());
            }
        }

        record.emit();
    }

    private static Severity toSeverity(String logLevel) {
        if (logLevel == null) {
            return Severity.UNDEFINED_SEVERITY_NUMBER;
        }
        switch (logLevel.toLowerCase()) {
            case "debug": return Severity.DEBUG;
            case "info":  return Severity.INFO;
            case "warn":  return Severity.WARN;
            case "error": return Severity.ERROR;
            default:      return Severity.INFO;
        }
    }

    private static OpenTelemetry initializeOpenTelemetry(Main main) {
        String collectorUri = Config.getBaseConfig(main).getOtelCollectorConnectionURI();

        // If a real OpenTelemetry SDK is already installed globally (e.g. by the
        // --javaagent), reuse it instead of building a duplicate. We must detect
        // this via the tracer provider, NOT via equals(noop()): GlobalOpenTelemetry
        // wraps whatever is set in a private ObfuscatedOpenTelemetry that does not
        // override equals(), so get().equals(OpenTelemetry.noop()) is NEVER true --
        // even when nothing real was installed and the wrapper merely delegates to
        // noop. The wrapper does delegate getTracerProvider(), so comparing against
        // the noop tracer provider correctly distinguishes "agent installed" from
        // "no agent". Without this, the agent-less path silently returns the noop
        // SDK and exports nothing.
        OpenTelemetry global = GlobalOpenTelemetry.get();
        if (global != null && global.getTracerProvider() != TracerProvider.noop()) {
            Logging.info(main, TenantIdentifier.BASE_TENANT,
                    "OpenTelemetry: reusing externally-installed global SDK (e.g. --javaagent); "
                            + "the core's built-in exporter is not used.", true);
            return global; // already initialized by a real SDK
        }

        if (collectorUri == null || collectorUri.isEmpty()) {
            Logging.info(main, TenantIdentifier.BASE_TENANT,
                    "OpenTelemetry telemetry disabled: no otel_collector_connection_uri configured.", true);
            return null;
        }

        Logging.info(main, TenantIdentifier.BASE_TENANT,
                "OpenTelemetry telemetry enabled: exporting spans and logs to " + collectorUri, true);

        if (getInstance(main) != null && getInstance(main).openTelemetry != null) {
            return getInstance(main).openTelemetry; // already initialized
        }


        Resource resource = Resource.getDefault().toBuilder()
                .put(SERVICE_NAME, "supertokens-core")
                .build();

        SdkTracerProvider sdkTracerProvider =
                SdkTracerProvider.builder()
                        .setResource(resource)
                        .addSpanProcessor(SimpleSpanProcessor.create(OtlpGrpcSpanExporter.builder()
                                .setEndpoint(collectorUri) // otel collector
                                .build()))
                        .build();

        OpenTelemetrySdk sdk =
                OpenTelemetrySdk.builder()
                        .setTracerProvider(sdkTracerProvider)
                        .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
                        .setLoggerProvider(
                                SdkLoggerProvider.builder()
                                        .setResource(resource)
                                        .addLogRecordProcessor(
                                                BatchLogRecordProcessor.builder(
                                                                OtlpGrpcLogRecordExporter.builder()
                                                                        .setEndpoint(collectorUri)
                                                                        .build())

                                                        .build())
                                        .build())
                        .build();

        // Add hook to close SDK, which flushes logs
        Runtime.getRuntime().addShutdownHook(new Thread(sdk::close));
        return sdk;
    }

    @TestOnly
    public static void resetForTest() {
        GlobalOpenTelemetry.resetForTest();
    }

    public static void closeTelemetry(Main main) {
        OpenTelemetry telemetry = getInstance(main).openTelemetry;
        if (telemetry instanceof OpenTelemetrySdk) {
            ((OpenTelemetrySdk) telemetry).close();
        }
    }

    private TelemetryProvider(Main main) {
        openTelemetry = initializeOpenTelemetry(main);
    }
}
