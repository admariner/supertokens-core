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

package io.supertokens.test.migration;

import io.supertokens.Main;
import io.supertokens.ProcessState;
import io.supertokens.migration.MigrationModeTransition;
import io.supertokens.pluginInterface.MigrationMode;
import io.supertokens.pluginInterface.Storage;
import io.supertokens.pluginInterface.exceptions.InvalidConfigException;
import io.supertokens.pluginInterface.exceptions.StorageQueryException;
import io.supertokens.pluginInterface.migration.MigrationBackfillStorage;
import io.supertokens.pluginInterface.multitenancy.AppIdentifier;
import io.supertokens.pluginInterface.sqlStorage.SQLStorage;
import io.supertokens.storageLayer.StorageLayer;
import io.supertokens.test.TestingProcessManager;
import io.supertokens.test.Utils;

import org.junit.AfterClass;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestRule;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Table-driven coverage of the {@link MigrationModeTransition} state machine.
 *
 * Allowed transitions:
 *   LEGACY              ↔  DUAL_WRITE_READ_OLD
 *   DUAL_WRITE_READ_OLD ↔  DUAL_WRITE_READ_NEW
 *   DUAL_WRITE_READ_NEW →  MIGRATED            (only when pendingUsers == 0)
 *   MIGRATED                                    (terminal)
 *
 * Every other (old, new) pair must be rejected.
 *
 * The → MIGRATED transition runs two probes — {@link MigrationBackfillStorage#getBackfillPendingUsersCount}
 * and {@link MigrationBackfillStorage#verifyBackfillCompleteness} — both of which return 0 on a
 * freshly-started in-memory process with no users, so the happy-path forward chain succeeds
 * end-to-end. The rejection paths are staged storage-agnostically with row surgery on a
 * signed-up user: a time_joined = 0 sentinel for the pending probe, and stripped
 * reservation rows (real time_joined kept) for the completeness scan.
 */
public class MigrationModeTransitionTest {

    @Rule
    public TestRule watchman = Utils.getOnFailure();

    @AfterClass
    public static void afterTesting() {
        Utils.afterTesting();
    }

    @Before
    public void beforeEach() {
        Utils.reset();
    }

    private TestingProcessManager.TestingProcess startProcess() throws Exception {
        String[] args = {"../"};
        TestingProcessManager.TestingProcess p = TestingProcessManager.start(args);
        assertNotNull(p.checkOrWaitForEvent(ProcessState.PROCESS_STATE.STARTED));
        return p;
    }

    private AppIdentifier publicApp() {
        return new AppIdentifier(null, null);
    }

    @Test
    public void noOpTransitionsAlwaysAllowed() throws Exception {
        TestingProcessManager.TestingProcess process = startProcess();
        try {
            for (MigrationMode m : MigrationMode.values()) {
                MigrationModeTransition.validate(process.getProcess(), publicApp(), m, m);
            }
        } finally {
            process.kill();
            assertNotNull(process.checkOrWaitForEvent(ProcessState.PROCESS_STATE.STOPPED));
        }
    }

    @Test
    public void oneStepForwardTransitionsAllowed() throws Exception {
        TestingProcessManager.TestingProcess process = startProcess();
        try {
            // LEGACY → DUAL_WRITE_READ_OLD
            MigrationModeTransition.validate(process.getProcess(), publicApp(),
                    MigrationMode.LEGACY, MigrationMode.DUAL_WRITE_READ_OLD);

            // DUAL_WRITE_READ_OLD → DUAL_WRITE_READ_NEW
            MigrationModeTransition.validate(process.getProcess(), publicApp(),
                    MigrationMode.DUAL_WRITE_READ_OLD, MigrationMode.DUAL_WRITE_READ_NEW);

            // DUAL_WRITE_READ_NEW → MIGRATED   (no users in fresh process, so pending==0)
            MigrationModeTransition.validate(process.getProcess(), publicApp(),
                    MigrationMode.DUAL_WRITE_READ_NEW, MigrationMode.MIGRATED);
        } finally {
            process.kill();
            assertNotNull(process.checkOrWaitForEvent(ProcessState.PROCESS_STATE.STOPPED));
        }
    }

    @Test
    public void oneStepBackwardTransitionsAllowedExceptFromMigrated() throws Exception {
        TestingProcessManager.TestingProcess process = startProcess();
        try {
            // DUAL_WRITE_READ_OLD → LEGACY
            MigrationModeTransition.validate(process.getProcess(), publicApp(),
                    MigrationMode.DUAL_WRITE_READ_OLD, MigrationMode.LEGACY);

            // DUAL_WRITE_READ_NEW → DUAL_WRITE_READ_OLD
            MigrationModeTransition.validate(process.getProcess(), publicApp(),
                    MigrationMode.DUAL_WRITE_READ_NEW, MigrationMode.DUAL_WRITE_READ_OLD);

            // MIGRATED → anything is rejected (terminal)
            for (MigrationMode target : new MigrationMode[]{
                    MigrationMode.LEGACY,
                    MigrationMode.DUAL_WRITE_READ_OLD,
                    MigrationMode.DUAL_WRITE_READ_NEW}) {
                assertRejected(process, MigrationMode.MIGRATED, target, "terminal");
            }
        } finally {
            process.kill();
            assertNotNull(process.checkOrWaitForEvent(ProcessState.PROCESS_STATE.STOPPED));
        }
    }

    @Test
    public void skipStateTransitionsRejected() throws Exception {
        TestingProcessManager.TestingProcess process = startProcess();
        try {
            // Forward skips
            assertRejected(process, MigrationMode.LEGACY, MigrationMode.DUAL_WRITE_READ_NEW, "one step at a time");
            assertRejected(process, MigrationMode.LEGACY, MigrationMode.MIGRATED, "one step at a time");
            assertRejected(process, MigrationMode.DUAL_WRITE_READ_OLD, MigrationMode.MIGRATED, "one step at a time");

            // Backward skips (note: → MIGRATED checks pending; backward from MIGRATED is rejected by terminal rule)
            assertRejected(process, MigrationMode.DUAL_WRITE_READ_NEW, MigrationMode.LEGACY, "one step at a time");
        } finally {
            process.kill();
            assertNotNull(process.checkOrWaitForEvent(ProcessState.PROCESS_STATE.STOPPED));
        }
    }

    @Test
    public void migratedTransitionRejectedWhenBackfillPending() throws Exception {
        TestingProcessManager.TestingProcess process = startProcess();
        try {
            Main main = process.getProcess();
            Storage storage = StorageLayer.getStorage(main);

            // Stage a pre-backfill row directly: the 12.0 schema migration leaves
            // time_joined = 0 (the backfill sentinel) on rows that predate the upgrade.
            // Raw-SQL staging keeps the test independent of the harness's migration
            // mode and of any signup write pattern.
            String uid = "10000000-0000-4000-8000-000000000001";
            insertAppIdToUserIdRow(storage, uid, 0);

            assertEquals(1, ((MigrationBackfillStorage) storage)
                    .getBackfillPendingUsersCount(publicApp()));

            assertRejected(process, MigrationMode.DUAL_WRITE_READ_NEW, MigrationMode.MIGRATED,
                    "still need backfilling");

            // Clean up the staged row — the test database is shared within this class —
            // and confirm the same transition is allowed once nothing is pending.
            executeUpdate(storage, "DELETE FROM " + APP_ID_TO_USER_ID_TABLE
                    + " WHERE user_id = '" + uid + "'");
            MigrationModeTransition.validate(main, publicApp(),
                    MigrationMode.DUAL_WRITE_READ_NEW, MigrationMode.MIGRATED);
        } finally {
            process.kill();
            assertNotNull(process.checkOrWaitForEvent(ProcessState.PROCESS_STATE.STOPPED));
        }
    }

    /**
     * A user missing reservation rows but with a real time_joined (the state LEGACY-mode
     * signups produced before the plugin kept the backfill sentinel) is invisible to the
     * pending count — only the verify scan sees it. The → MIGRATED transition must be
     * refused: crossing it removes the old tables from the read path, making such users
     * unreachable.
     */
    @Test
    public void migratedTransitionRejectedWhenInconsistentUsersExist() throws Exception {
        TestingProcessManager.TestingProcess process = startProcess();
        try {
            Main main = process.getProcess();
            Storage storage = StorageLayer.getStorage(main);

            // Real time_joined, no reservation rows — the damaged shape.
            String uid = "10000000-0000-4000-8000-000000000002";
            insertAppIdToUserIdRow(storage, uid, 1234);

            MigrationBackfillStorage backfillStorage = (MigrationBackfillStorage) storage;
            assertEquals(0, backfillStorage.getBackfillPendingUsersCount(publicApp()));
            assertEquals(1, backfillStorage.verifyBackfillCompleteness(publicApp()));

            assertRejected(process, MigrationMode.DUAL_WRITE_READ_NEW, MigrationMode.MIGRATED,
                    "inconsistent");

            // Remediate (here: drop the orphan entirely) and the same transition is allowed.
            executeUpdate(storage, "DELETE FROM " + APP_ID_TO_USER_ID_TABLE
                    + " WHERE user_id = '" + uid + "'");
            assertEquals(0, backfillStorage.verifyBackfillCompleteness(publicApp()));
            MigrationModeTransition.validate(main, publicApp(),
                    MigrationMode.DUAL_WRITE_READ_NEW, MigrationMode.MIGRATED);
        } finally {
            process.kill();
            assertNotNull(process.checkOrWaitForEvent(ProcessState.PROCESS_STATE.STOPPED));
        }
    }

    // Default table name, shared by the in-memory schema and the plugin defaults used in
    // CI (no custom table-name prefixes are configured in the test env).
    private static final String APP_ID_TO_USER_ID_TABLE = "app_id_to_user_id";

    private static void insertAppIdToUserIdRow(Storage storage, String userId, long timeJoined)
            throws Exception {
        executeUpdate(storage, "INSERT INTO " + APP_ID_TO_USER_ID_TABLE
                + " (app_id, user_id, recipe_id, primary_or_recipe_user_id,"
                + "  time_joined, primary_or_recipe_user_time_joined)"
                + " VALUES ('public', '" + userId + "', 'emailpassword', '" + userId + "', "
                + timeJoined + ", " + timeJoined + ")");
    }

    private static void executeUpdate(Storage storage, String sql) throws Exception {
        int affected = ((SQLStorage) storage).startTransaction(con -> {
            Connection sqlCon = (Connection) con.getConnection();
            try (Statement stmt = sqlCon.createStatement()) {
                int rows = stmt.executeUpdate(sql);
                sqlCon.commit();
                return rows;
            } catch (SQLException e) {
                throw new StorageQueryException(e);
            }
        });
        assertTrue("staging statement matched no rows: " + sql, affected >= 1);
    }

    @Test
    public void allMatrixCombinationsHaveDeterministicOutcome() throws Exception {
        // Sanity: every (old, new) pair either succeeds or throws InvalidConfigException —
        // no NPEs, no unchecked, no infinite loops. Covers the full 4x4 = 16 combinations.
        TestingProcessManager.TestingProcess process = startProcess();
        try {
            for (MigrationMode oldM : MigrationMode.values()) {
                for (MigrationMode newM : MigrationMode.values()) {
                    try {
                        MigrationModeTransition.validate(process.getProcess(), publicApp(), oldM, newM);
                    } catch (InvalidConfigException e) {
                        // expected for rejected transitions
                    } catch (RuntimeException e) {
                        fail(oldM + " → " + newM + " threw " + e + ", expected only InvalidConfigException");
                    }
                }
            }
        } finally {
            process.kill();
            assertNotNull(process.checkOrWaitForEvent(ProcessState.PROCESS_STATE.STOPPED));
        }
    }

    private void assertRejected(TestingProcessManager.TestingProcess process,
                                MigrationMode oldMode, MigrationMode newMode,
                                String expectedMessageFragment) {
        try {
            MigrationModeTransition.validate(process.getProcess(), publicApp(), oldMode, newMode);
            fail("Expected InvalidConfigException for " + oldMode + " → " + newMode);
        } catch (InvalidConfigException e) {
            assertTrue("Expected message to contain '" + expectedMessageFragment + "', got: " + e.getMessage(),
                    e.getMessage().toLowerCase().contains(expectedMessageFragment.toLowerCase()));
        }
    }
}
