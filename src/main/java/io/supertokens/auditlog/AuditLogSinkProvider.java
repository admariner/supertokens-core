/*
 *    Copyright (c) 2026, VRAI Labs and/or its affiliates. All rights reserved.
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

package io.supertokens.auditlog;

import io.supertokens.Main;
import io.supertokens.ResourceDistributor;
import io.supertokens.output.Logging;
import io.supertokens.pluginInterface.auditlog.AuditLogEvent;
import io.supertokens.pluginInterface.auditlog.AuditLogSink;
import io.supertokens.pluginInterface.multitenancy.TenantIdentifier;
import io.supertokens.pluginInterface.multitenancy.exceptions.TenantOrAppNotFoundException;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class AuditLogSinkProvider extends ResourceDistributor.SingletonResource {

    private static URLClassLoader ucl = null;

    private final Main main;
    private final List<AuditLogSink> sinks;
    private final ExecutorService executor;

    private AuditLogSinkProvider(Main main, String pluginFolderPath) throws MalformedURLException {
        this.main = main;
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "audit-log-sink");
            t.setDaemon(true);
            return t;
        });

        if (ucl == null) {
            File dir = new File(pluginFolderPath);
            File[] jars = dir.listFiles(f -> f.getName().toLowerCase().endsWith(".jar"));
            if (jars != null && jars.length > 0) {
                URL[] urls = new URL[jars.length];
                for (int i = 0; i < jars.length; i++) {
                    urls[i] = jars[i].toURI().toURL();
                }
                ucl = new URLClassLoader(urls);
            }
        }

        this.sinks = loadSinks();
    }

    private List<AuditLogSink> loadSinks() {
        if (ucl == null) {
            return List.of();
        }
        List<AuditLogSink> result = new ArrayList<>();
        for (AuditLogSink sink : ServiceLoader.load(AuditLogSink.class, ucl)) {
            result.add(sink);
        }
        return result;
    }

    public static void initialize(Main main, String pluginFolderPath) throws MalformedURLException {
        main.getResourceDistributor()
                .setResource(TenantIdentifier.BASE_TENANT, AuditLogSink.RESOURCE_ID,
                        new AuditLogSinkProvider(main, pluginFolderPath));
    }

    public static AuditLogSinkProvider getInstance(Main main) {
        try {
            return (AuditLogSinkProvider) main.getResourceDistributor()
                    .getResource(TenantIdentifier.BASE_TENANT, AuditLogSink.RESOURCE_ID);
        } catch (TenantOrAppNotFoundException e) {
            return null;
        }
    }

    /**
     * Dispatches the event to all registered sinks asynchronously, preserving insertion order.
     * A missing or empty sink list is a no-op.
     */
    public void publishEvent(AuditLogEvent event) {
        if (sinks.isEmpty()) {
            return;
        }
        executor.submit(() -> {
            for (AuditLogSink sink : sinks) {
                try {
                    sink.onEvent(event);
                } catch (Exception e) {
                    Logging.error(main, TenantIdentifier.BASE_TENANT,
                            "AuditLogSink " + sink.getClass().getName() + " threw: " + e.getMessage(), false);
                }
            }
        });
    }

    public void close() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
