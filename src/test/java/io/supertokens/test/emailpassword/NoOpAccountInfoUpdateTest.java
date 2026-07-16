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

package io.supertokens.test.emailpassword;

import io.supertokens.Main;
import io.supertokens.ProcessState;
import io.supertokens.authRecipe.AuthRecipe;
import io.supertokens.emailpassword.EmailPassword;
import io.supertokens.emailpassword.exceptions.WrongCredentialsException;
import io.supertokens.featureflag.EE_FEATURES;
import io.supertokens.featureflag.FeatureFlagTestContent;
import io.supertokens.inmemorydb.ConnectionPool;
import io.supertokens.inmemorydb.Start;
import io.supertokens.multitenancy.Multitenancy;
import io.supertokens.passwordless.Passwordless;
import io.supertokens.pluginInterface.MigrationMode;
import io.supertokens.pluginInterface.STORAGE_TYPE;
import io.supertokens.pluginInterface.Storage;
import io.supertokens.pluginInterface.authRecipe.AuthRecipeUserInfo;
import io.supertokens.pluginInterface.emailpassword.exceptions.DuplicateEmailException;
import io.supertokens.pluginInterface.migration.MigrationBackfillStorage;
import io.supertokens.pluginInterface.passwordless.exception.DuplicatePhoneNumberException;
import io.supertokens.pluginInterface.multitenancy.*;
import io.supertokens.storageLayer.StorageLayer;
import io.supertokens.test.TestingProcessManager;
import io.supertokens.test.Utils;
import io.supertokens.thirdparty.ThirdParty;

import com.google.gson.JsonObject;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import org.junit.AfterClass;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestRule;

import static org.junit.Assert.*;

/**
 * Regression tests for no-op account-info updates across migration modes. This is a
 * storage-contract test: every storage backend (in-memory, postgresql, mysql, ...) must
 * conform, which is why it lives in the core suite rather than a plugin's.
 *
 * updateUsersEmailOrPassword runs the email update before the password update inside one
 * transaction. In any mode that writes to the new tables (DUAL_WRITE_* / MIGRATED),
 * updateAccountInfo_Transaction derives the replacement recipe_user_tenants rows from the
 * user's existing rows, substituting the new account_info_value. Because the table's PK
 * includes account_info_value, an update to the SAME value inserts rows byte-identical to
 * the existing ones -> PK collision -> the whole transaction (including the password
 * update) rolls back and the core reports EMAIL_ALREADY_EXISTS_ERROR.
 *
 * This is a self-collision, not a conflict with another user: any unchanged-value email
 * (or phone number) update hits it. SDKs routinely send the unchanged email alongside a
 * new password, so a plain "change password" call silently fails to change the password.
 *
 * In LEGACY mode the legacy path just re-runs UPDATE ... SET email = ?, which is
 * idempotent, so the same call succeeds.
 *
 * The same storage function serves passwordless email/phone updates
 * (updateUserEmailAndPhone_Transaction), so the phone-number variant is covered here too:
 * a passwordless updateUser that adds an email while passing the unchanged phone number
 * must not abort on a phone self-collision.
 */
public class NoOpAccountInfoUpdateTest {

    @Rule
    public TestRule watchman = Utils.getOnFailure();

    @Rule
    public TestRule retryFlaky = Utils.retryFlakyTest();

    @AfterClass
    public static void afterTesting() {
        Utils.afterTesting();
    }

    @Before
    public void beforeEach() {
        Utils.reset();
    }

    /**
     * Signs up a user, then calls updateUsersEmailOrPassword with the UNCHANGED email and
     * a new password — the exact call shape the node SDK produces for "change password".
     * The update must succeed and the new password must be live.
     */
    private void runSameEmailNewPasswordScenario(MigrationMode mode) throws Exception {
        // Persisted config (not a test backdoor) so every storage backend takes the same
        // production path to pick up the mode.
        Utils.setValueInConfig("migration_mode", mode.name());
        String[] args = {"../"};
        TestingProcessManager.TestingProcess process = TestingProcessManager.startIsolatedProcess(args, false);
        process.startProcess();
        assertNotNull(process.checkOrWaitForEvent(ProcessState.PROCESS_STATE.STARTED));
        try {
            Main main = process.getProcess();
            if (StorageLayer.getStorage(main).getType() != STORAGE_TYPE.SQL) {
                return;
            }

            // The in-memory storage does not honor migration_mode from the config: it reports a
            // hardcoded MIGRATED (SQLiteConfig.getMigrationMode), so only the MIGRATED variant
            // can be exercised on it. The other modes run against the real plugins.
            if (StorageLayer.isInMemDb(main) && mode != MigrationMode.MIGRATED) {
                return;
            }

            // make sure the config override was actually picked up, otherwise all variants
            // of this test would silently run in the same mode
            assertEquals(mode,
                    ((MigrationBackfillStorage) StorageLayer.getStorage(main)).getMigrationMode());

            AuthRecipeUserInfo user = EmailPassword.signUp(main, "test@example.com", "password1");
            String uid = user.getSupertokensUserId();

            try {
                EmailPassword.updateUsersEmailOrPassword(main, uid, "test@example.com", "newpassword1");
            } catch (DuplicateEmailException e) {
                fail("mode=" + mode + ": updating a user's email to its current (unchanged) value must not"
                        + " be reported as a duplicate email — expected self-collision on the"
                        + " recipe_user_tenants PK rolled back the whole update");
            }

            // The password update runs in the same transaction as the email update; if the
            // email step aborted it, the old password is still in effect.
            try {
                AuthRecipeUserInfo signedIn = EmailPassword.signIn(main, "test@example.com", "newpassword1");
                assertEquals(uid, signedIn.getSupertokensUserId());
            } catch (WrongCredentialsException e) {
                fail("mode=" + mode + ": sign in with the new password failed — the password update was"
                        + " rolled back together with the no-op email update");
            }

            try {
                EmailPassword.signIn(main, "test@example.com", "password1");
                fail("mode=" + mode + ": sign in with the OLD password still works — the password was never updated");
            } catch (WrongCredentialsException ignored) {
                // expected: old password no longer valid
            }
        } finally {
            process.kill();
            assertNotNull(process.checkOrWaitForEvent(ProcessState.PROCESS_STATE.STOPPED));
        }
    }

    /**
     * Control: LEGACY mode passes today, because the legacy path is an idempotent
     * UPDATE ... SET email = ?.
     */
    @Test
    public void testSameEmailUpdateWithNewPasswordInLegacyMode() throws Exception {
        runSameEmailNewPasswordScenario(MigrationMode.LEGACY);
    }

    @Test
    public void testSameEmailUpdateWithNewPasswordInDualWriteReadOldMode() throws Exception {
        runSameEmailNewPasswordScenario(MigrationMode.DUAL_WRITE_READ_OLD);
    }

    @Test
    public void testSameEmailUpdateWithNewPasswordInDualWriteReadNewMode() throws Exception {
        runSameEmailNewPasswordScenario(MigrationMode.DUAL_WRITE_READ_NEW);
    }

    @Test
    public void testSameEmailUpdateWithNewPasswordInMigratedMode() throws Exception {
        runSameEmailNewPasswordScenario(MigrationMode.MIGRATED);
    }

    /**
     * Mirrors the original failing SDK test: three tenants (t1, t2, t3), each with its own
     * unlinked user under the same email. Changing t1's user's password (with the unchanged
     * email sent along) must succeed and must not touch the other tenants' users.
     *
     * The failure is a SELF-collision on t1's own recipe_user_tenants row, not a conflict
     * with the same-email users on t2/t3 — but this setup also guards against any future
     * cross-tenant confusion in the same code path.
     */
    @Test
    public void testSameEmailUpdateWithMultipleTenantsHoldingSameEmail() throws Exception {
        // Persisted config so the mode survives the storage refresh triggered by
        // tenant creation below.
        Utils.setValueInConfig("migration_mode", MigrationMode.DUAL_WRITE_READ_OLD.name());
        String[] args = {"../"};
        TestingProcessManager.TestingProcess process = TestingProcessManager.startIsolatedProcess(args, false);
        FeatureFlagTestContent.getInstance(process.getProcess())
                .setKeyValue(FeatureFlagTestContent.ENABLED_FEATURES,
                        new EE_FEATURES[]{EE_FEATURES.ACCOUNT_LINKING, EE_FEATURES.MULTI_TENANCY});
        process.startProcess();
        assertNotNull(process.checkOrWaitForEvent(ProcessState.PROCESS_STATE.STARTED));
        try {
            Main main = process.getProcess();
            if (StorageLayer.getStorage(main).getType() != STORAGE_TYPE.SQL) {
                return;
            }
            // in-memory hardcodes MIGRATED; DUAL_WRITE_READ_OLD can't be exercised on it
            if (StorageLayer.isInMemDb(main)) {
                return;
            }
            assertEquals(MigrationMode.DUAL_WRITE_READ_OLD,
                    ((MigrationBackfillStorage) StorageLayer.getStorage(main)).getMigrationMode());

            String[] tenantIds = {"t1", "t2", "t3"};
            TenantIdentifier[] tenants = new TenantIdentifier[tenantIds.length];
            Storage[] tenantStorages = new Storage[tenantIds.length];
            String[] userIds = new String[tenantIds.length];

            for (int i = 0; i < tenantIds.length; i++) {
                tenants[i] = new TenantIdentifier(null, null, tenantIds[i]);
                Multitenancy.addNewOrUpdateAppOrTenant(main, new TenantConfig(
                        tenants[i],
                        new EmailPasswordConfig(true),
                        new ThirdPartyConfig(true, null),
                        new PasswordlessConfig(true),
                        null, null, new JsonObject()
                ), false);
                tenantStorages[i] = StorageLayer.getStorage(tenants[i], main);

                AuthRecipeUserInfo user = EmailPassword.signUp(tenants[i], tenantStorages[i], main,
                        "test@example.com", "password1");
                userIds[i] = user.getSupertokensUserId();
            }

            // Change only t1's user's password, passing the unchanged email along —
            // the same call shape the node SDK's updateEmailOrPassword produces.
            try {
                EmailPassword.updateUsersEmailOrPassword(tenants[0].toAppIdentifier(), tenantStorages[0], main,
                        userIds[0], "test@example.com", "newpassword1");
            } catch (DuplicateEmailException e) {
                fail("updating t1's user with its own unchanged email must not be reported as a"
                        + " duplicate email — the same-email users on t2/t3 are different recipe users"
                        + " and the value did not change");
            }

            // t1: new password works, old one doesn't.
            AuthRecipeUserInfo signedIn = null;
            try {
                signedIn = EmailPassword.signIn(tenants[0], tenantStorages[0], main,
                        "test@example.com", "newpassword1");
            } catch (WrongCredentialsException e) {
                fail("sign in on t1 with the new password failed — the password update was rolled"
                        + " back together with the no-op email update");
            }
            assertEquals(userIds[0], signedIn.getSupertokensUserId());
            try {
                EmailPassword.signIn(tenants[0], tenantStorages[0], main, "test@example.com", "password1");
                fail("sign in on t1 with the OLD password still works — the password was never updated");
            } catch (WrongCredentialsException ignored) {
                // expected
            }

            // t2 and t3 users must be untouched: their original password still works.
            for (int i = 1; i < tenantIds.length; i++) {
                AuthRecipeUserInfo other = EmailPassword.signIn(tenants[i], tenantStorages[i], main,
                        "test@example.com", "password1");
                assertEquals(userIds[i], other.getSupertokensUserId());
            }
        } finally {
            process.kill();
            assertNotNull(process.checkOrWaitForEvent(ProcessState.PROCESS_STATE.STOPPED));
        }
    }

    /**
     * Passwordless variant of the same defect. updateUserEmailAndPhone_Transaction runs the
     * email step and the phone step in one transaction, and in new-table modes both go
     * through the same updateAccountInfo_Transaction that self-collides on unchanged values.
     *
     * Signs up a phone-only passwordless user, then calls updateUser adding an email while
     * passing the UNCHANGED phone number — the call shape SDKs produce when a user adds an
     * email to their account. The no-op phone update must not be reported as a duplicate
     * phone number, and the email write in the same transaction must land.
     */
    private void runSamePhoneAddEmailScenario(MigrationMode mode) throws Exception {
        Utils.setValueInConfig("migration_mode", mode.name());
        String[] args = {"../"};
        TestingProcessManager.TestingProcess process = TestingProcessManager.startIsolatedProcess(args, false);
        process.startProcess();
        assertNotNull(process.checkOrWaitForEvent(ProcessState.PROCESS_STATE.STARTED));
        try {
            Main main = process.getProcess();
            if (StorageLayer.getStorage(main).getType() != STORAGE_TYPE.SQL) {
                return;
            }
            // in-memory hardcodes MIGRATED; see runSameEmailNewPasswordScenario
            if (StorageLayer.isInMemDb(main) && mode != MigrationMode.MIGRATED) {
                return;
            }
            assertEquals(mode,
                    ((MigrationBackfillStorage) StorageLayer.getStorage(main)).getMigrationMode());

            String phoneNumber = "+442071838750";
            Passwordless.CreateCodeResponse code = Passwordless.createCode(main, null, phoneNumber, null, null);
            Passwordless.ConsumeCodeResponse consumed = Passwordless.consumeCode(main,
                    code.deviceId, code.deviceIdHash, code.userInputCode, null);
            String uid = consumed.user.getSupertokensUserId();

            try {
                Passwordless.updateUser(main, uid,
                        new Passwordless.FieldUpdate("added@example.com"),
                        new Passwordless.FieldUpdate(phoneNumber));
            } catch (DuplicatePhoneNumberException e) {
                fail("mode=" + mode + ": updating a user's phone number to its current (unchanged) value"
                        + " must not be reported as a duplicate phone number — expected self-collision on"
                        + " the recipe_user_tenants PK rolled back the whole update");
            }

            // The email update runs in the same transaction as the phone update; if the
            // phone step aborted it, the email was never added.
            AuthRecipeUserInfo user = AuthRecipe.getUserById(main, uid);
            assertEquals(phoneNumber, user.loginMethods[0].phoneNumber);
            assertEquals("mode=" + mode + ": the email added alongside the no-op phone update was rolled back",
                    "added@example.com", user.loginMethods[0].email);
        } finally {
            process.kill();
            assertNotNull(process.checkOrWaitForEvent(ProcessState.PROCESS_STATE.STOPPED));
        }
    }

    /**
     * Control: LEGACY mode passes today (idempotent legacy UPDATE plus a plain conflict
     * check that ignores the user's own row).
     */
    @Test
    public void testSamePhoneUpdateWithNewEmailInLegacyMode() throws Exception {
        runSamePhoneAddEmailScenario(MigrationMode.LEGACY);
    }

    @Test
    public void testSamePhoneUpdateWithNewEmailInDualWriteReadOldMode() throws Exception {
        runSamePhoneAddEmailScenario(MigrationMode.DUAL_WRITE_READ_OLD);
    }

    @Test
    public void testSamePhoneUpdateWithNewEmailInDualWriteReadNewMode() throws Exception {
        runSamePhoneAddEmailScenario(MigrationMode.DUAL_WRITE_READ_NEW);
    }

    @Test
    public void testSamePhoneUpdateWithNewEmailInMigratedMode() throws Exception {
        runSamePhoneAddEmailScenario(MigrationMode.MIGRATED);
    }

    private static final String THIRD_PARTY_ID = "google";
    private static final String THIRD_PARTY_USER_ID = "tp-user-1";

    /**
     * Thirdparty variant, covering the source-row filters in QUERY_2_INSERT /
     * QUERY_2_UPSERT of updateAccountInfo_Transaction. For thirdparty users the new
     * tables hold TWO rows per tenant — an 'email' row carrying the real
     * third_party_id/user_id and a 'tparty' row with empty tp ids. An email change must
     * derive replacement rows only from the 'email' row: without the filter a second,
     * spurious 'email' row with empty tp ids is silently inserted (no error is raised,
     * because the differing tp ids are part of the PK).
     *
     * Because the corruption is silent, behavioral assertions alone can't catch it: the
     * test also asserts the exact row set in recipe_user_tenants /
     * recipe_user_account_infos after each change. Row-level inspection is only wired up
     * for the in-memory db (which runs MIGRATED mode); on real plugins the behavioral
     * assertions still run.
     *
     * The email is changed twice (a -> b -> c) because a spurious row left by the first
     * change only multiplies through subsequent derivations, and finished with a
     * same-email signInUp, which must remain a successful no-op.
     */
    private void runThirdPartyEmailChangeScenario(MigrationMode mode) throws Exception {
        Utils.setValueInConfig("migration_mode", mode.name());
        String[] args = {"../"};
        TestingProcessManager.TestingProcess process = TestingProcessManager.startIsolatedProcess(args, false);
        process.startProcess();
        assertNotNull(process.checkOrWaitForEvent(ProcessState.PROCESS_STATE.STARTED));
        try {
            Main main = process.getProcess();
            if (StorageLayer.getStorage(main).getType() != STORAGE_TYPE.SQL) {
                return;
            }
            // in-memory hardcodes MIGRATED; see runSameEmailNewPasswordScenario
            if (StorageLayer.isInMemDb(main) && mode != MigrationMode.MIGRATED) {
                return;
            }
            assertEquals(mode,
                    ((MigrationBackfillStorage) StorageLayer.getStorage(main)).getMigrationMode());

            ThirdParty.SignInUpResponse signUp = ThirdParty.signInUp(main, THIRD_PARTY_ID, THIRD_PARTY_USER_ID,
                    "a@example.com");
            String uid = signUp.user.getSupertokensUserId();
            if (mode.writesToNewTables()) {
                assertCanonicalThirdPartyRows(main, uid, "a@example.com");
            }

            // First email change: this is the path that derives replacement new-table
            // rows from the user's existing rows.
            ThirdParty.SignInUpResponse changed = ThirdParty.signInUp(main, THIRD_PARTY_ID, THIRD_PARTY_USER_ID,
                    "b@example.com");
            assertEquals(uid, changed.user.getSupertokensUserId());
            assertEquals("b@example.com", changed.user.loginMethods[0].email);
            if (mode.writesToNewTables()) {
                assertCanonicalThirdPartyRows(main, uid, "b@example.com");
            }

            // Second change: a spurious row left by the first change would be picked up
            // as a source row here and multiply.
            ThirdParty.SignInUpResponse changedAgain = ThirdParty.signInUp(main, THIRD_PARTY_ID, THIRD_PARTY_USER_ID,
                    "c@example.com");
            assertEquals(uid, changedAgain.user.getSupertokensUserId());
            assertEquals("c@example.com", changedAgain.user.loginMethods[0].email);
            if (mode.writesToNewTables()) {
                assertCanonicalThirdPartyRows(main, uid, "c@example.com");
            }

            // No-op: signing in again with the unchanged email must succeed and change
            // nothing.
            ThirdParty.SignInUpResponse noOp = ThirdParty.signInUp(main, THIRD_PARTY_ID, THIRD_PARTY_USER_ID,
                    "c@example.com");
            assertEquals(uid, noOp.user.getSupertokensUserId());
            assertEquals("c@example.com", noOp.user.loginMethods[0].email);
            if (mode.writesToNewTables()) {
                assertCanonicalThirdPartyRows(main, uid, "c@example.com");
            }
        } finally {
            process.kill();
            assertNotNull(process.checkOrWaitForEvent(ProcessState.PROCESS_STATE.STOPPED));
        }
    }

    /**
     * Asserts that the user has EXACTLY ONE 'email' row in recipe_user_tenants and in
     * recipe_user_account_infos, carrying the real third-party ids and the expected
     * value. A second 'email' row with empty tp ids is the corruption the source-row
     * filters prevent. In-memory db only; no-op on other storages.
     */
    private void assertCanonicalThirdPartyRows(Main main, String userId, String expectedEmail) throws Exception {
        if (!StorageLayer.isInMemDb(main)) {
            return;
        }
        Start start = (Start) StorageLayer.getStorage(main);
        try (Connection con = ConnectionPool.getConnection(start)) {
            for (String table : new String[]{"recipe_user_tenants", "recipe_user_account_infos"}) {
                try (PreparedStatement pst = con.prepareStatement(
                        "SELECT third_party_id, third_party_user_id, account_info_value FROM " + table
                                + " WHERE app_id = 'public' AND recipe_user_id = ? AND account_info_type = 'email'")) {
                    pst.setString(1, userId);
                    try (ResultSet result = pst.executeQuery()) {
                        assertTrue("expected an 'email' row in " + table, result.next());
                        assertEquals("the 'email' row in " + table + " must carry the real third_party_id",
                                THIRD_PARTY_ID, result.getString("third_party_id"));
                        assertEquals("the 'email' row in " + table + " must carry the real third_party_user_id",
                                THIRD_PARTY_USER_ID, result.getString("third_party_user_id"));
                        assertEquals(expectedEmail, result.getString("account_info_value"));
                        assertFalse("spurious extra 'email' row in " + table
                                        + " — the source-row filter in updateAccountInfo_Transaction is not working",
                                result.next());
                    }
                }
            }
        }
    }

    /**
     * Control: LEGACY mode does not write to the new tables, so only the behavioral
     * assertions apply.
     */
    @Test
    public void testThirdPartyEmailChangeInLegacyMode() throws Exception {
        runThirdPartyEmailChangeScenario(MigrationMode.LEGACY);
    }

    @Test
    public void testThirdPartyEmailChangeInDualWriteReadOldMode() throws Exception {
        runThirdPartyEmailChangeScenario(MigrationMode.DUAL_WRITE_READ_OLD);
    }

    @Test
    public void testThirdPartyEmailChangeInDualWriteReadNewMode() throws Exception {
        runThirdPartyEmailChangeScenario(MigrationMode.DUAL_WRITE_READ_NEW);
    }

    @Test
    public void testThirdPartyEmailChangeInMigratedMode() throws Exception {
        runThirdPartyEmailChangeScenario(MigrationMode.MIGRATED);
    }
}
