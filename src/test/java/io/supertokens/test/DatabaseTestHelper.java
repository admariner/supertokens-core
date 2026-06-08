/*
 *    Copyright (c) 2020, VRAI Labs and/or its affiliates. All rights reserved.
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
 *
 */

package io.supertokens.test;

import io.supertokens.pluginInterface.testUtils.TestDatabaseHelper;

import java.util.ServiceLoader;

/**
 * Thin facade over {@link TestDatabaseHelper}.
 *
 * The concrete implementation lives in supertokens-postgresql-plugin's testFixtures source set
 * ({@code PostgreSQLTestDatabaseHelper}) and is discovered at runtime via ServiceLoader.
 * This keeps the PostgreSQL JDBC driver out of supertokens-core's dependencies entirely.
 *
 * If no implementation is found on the classpath (e.g. in-memory-only test runs) all methods
 * are no-ops or return {@code null}, and tests fall back to the default configured database.
 */
public class DatabaseTestHelper {

    private static final TestDatabaseHelper IMPL = loadImpl();

    private static TestDatabaseHelper loadImpl() {
        return ServiceLoader.load(TestDatabaseHelper.class).findFirst().orElse(null);
    }

    public static String createTestDatabase() {
        if (IMPL == null) return null;
        return IMPL.createTestDatabase();
    }

    public static void dropCurrentTestDatabase() {
        if (IMPL != null) IMPL.dropCurrentTestDatabase();
    }

    public static String getCurrentTestDatabase() {
        if (IMPL == null) return null;
        return IMPL.getCurrentTestDatabase();
    }

    public static String getHost() {
        if (IMPL == null) return "localhost";
        return IMPL.getHost();
    }

    public static String getPort() {
        if (IMPL == null) return "5432";
        return IMPL.getPort();
    }

    public static String getUser() {
        if (IMPL == null) return "root";
        return IMPL.getUser();
    }

    public static String getPassword() {
        if (IMPL == null) return "root";
        return IMPL.getPassword();
    }
}
