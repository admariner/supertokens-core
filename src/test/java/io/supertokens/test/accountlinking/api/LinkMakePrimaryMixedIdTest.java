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

package io.supertokens.test.accountlinking.api;

import com.google.gson.JsonObject;
import io.supertokens.ProcessState;
import io.supertokens.emailpassword.EmailPassword;
import io.supertokens.featureflag.EE_FEATURES;
import io.supertokens.featureflag.FeatureFlagTestContent;
import io.supertokens.pluginInterface.STORAGE_TYPE;
import io.supertokens.pluginInterface.authRecipe.AuthRecipeUserInfo;
import io.supertokens.storageLayer.StorageLayer;
import io.supertokens.test.TestingProcessManager;
import io.supertokens.test.Utils;
import io.supertokens.test.httpRequest.HttpRequestForTesting;
import io.supertokens.useridmapping.UserIdMapping;
import io.supertokens.webserver.WebserverAPI;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestRule;

import static org.junit.Assert.*;

/**
 * Validates that the make-primary and link APIs accept BOTH external (mapped) and internal
 * (SuperTokens) user ids interchangeably, including mixing the two id forms within a single
 * link call.
 *
 * The existing API tests cover all-external and all-internal cases. The mixed case (one
 * external id + one internal id on the same /link call) was uncovered, and is exactly the
 * kind of input that arises during bulk/performance testing where the caller may hold ids in
 * either form. Each id is resolved independently at the API layer via UserIdType.ANY, so both
 * forms should work in any combination.
 */
public class LinkMakePrimaryMixedIdTest {
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

    private TestingProcessManager.TestingProcess startProcess() throws Exception {
        String[] args = {"../"};
        TestingProcessManager.TestingProcess process = TestingProcessManager.start(args);
        FeatureFlagTestContent.getInstance(process.getProcess())
                .setKeyValue(FeatureFlagTestContent.ENABLED_FEATURES, new EE_FEATURES[]{
                        EE_FEATURES.ACCOUNT_LINKING, EE_FEATURES.MULTI_TENANCY});
        assertNotNull(process.checkOrWaitForEvent(ProcessState.PROCESS_STATE.STARTED));
        return process;
    }

    private JsonObject makePrimary(TestingProcessManager.TestingProcess process, String recipeUserId)
            throws Exception {
        JsonObject params = new JsonObject();
        params.addProperty("recipeUserId", recipeUserId);
        return HttpRequestForTesting.sendJsonPOSTRequest(process.getProcess(), "",
                "http://localhost:3567/recipe/accountlinking/user/primary", params, 1000, 1000, null,
                WebserverAPI.getLatestCDIVersion().get(), "");
    }

    private JsonObject link(TestingProcessManager.TestingProcess process, String recipeUserId, String primaryUserId)
            throws Exception {
        JsonObject params = new JsonObject();
        params.addProperty("recipeUserId", recipeUserId);
        params.addProperty("primaryUserId", primaryUserId);
        return HttpRequestForTesting.sendJsonPOSTRequest(process.getProcess(), "",
                "http://localhost:3567/recipe/accountlinking/user/link", params, 1000, 1000, null,
                WebserverAPI.getLatestCDIVersion().get(), "");
    }

    // make-primary using the EXTERNAL id, then link using EXTERNAL recipe id + INTERNAL primary id.
    @Test
    public void testLinkExternalRecipeWithInternalPrimary() throws Exception {
        TestingProcessManager.TestingProcess process = startProcess();
        if (StorageLayer.getStorage(process.getProcess()).getType() != STORAGE_TYPE.SQL) {
            return;
        }

        AuthRecipeUserInfo recipeUser = EmailPassword.signUp(process.getProcess(), "test1@example.com", "abcd1234");
        UserIdMapping.createUserIdMapping(process.getProcess(), recipeUser.getSupertokensUserId(), "ext1", null, false);

        AuthRecipeUserInfo primaryUser = EmailPassword.signUp(process.getProcess(), "test2@example.com", "abcd1234");
        UserIdMapping.createUserIdMapping(process.getProcess(), primaryUser.getSupertokensUserId(), "ext2", null, false);

        // make primary using the EXTERNAL id
        JsonObject mp = makePrimary(process, "ext2");
        assertEquals("OK", mp.get("status").getAsString());
        assertFalse(mp.get("wasAlreadyAPrimaryUser").getAsBoolean());

        // link using EXTERNAL recipe id + INTERNAL primary id
        JsonObject response = link(process, "ext1", primaryUser.getSupertokensUserId());
        assertEquals("OK", response.get("status").getAsString());
        assertFalse(response.get("accountsAlreadyLinked").getAsBoolean());

        // returned primary user must now have both login methods linked
        JsonObject jsonUser = response.get("user").getAsJsonObject();
        assertEquals(2, jsonUser.get("loginMethods").getAsJsonArray().size());

        process.kill();
        assertNotNull(process.checkOrWaitForEvent(ProcessState.PROCESS_STATE.STOPPED));
    }

    // make-primary using the INTERNAL id, then link using INTERNAL recipe id + EXTERNAL primary id.
    @Test
    public void testLinkInternalRecipeWithExternalPrimary() throws Exception {
        TestingProcessManager.TestingProcess process = startProcess();
        if (StorageLayer.getStorage(process.getProcess()).getType() != STORAGE_TYPE.SQL) {
            return;
        }

        AuthRecipeUserInfo recipeUser = EmailPassword.signUp(process.getProcess(), "test3@example.com", "abcd1234");
        UserIdMapping.createUserIdMapping(process.getProcess(), recipeUser.getSupertokensUserId(), "ext3", null, false);

        AuthRecipeUserInfo primaryUser = EmailPassword.signUp(process.getProcess(), "test4@example.com", "abcd1234");
        UserIdMapping.createUserIdMapping(process.getProcess(), primaryUser.getSupertokensUserId(), "ext4", null, false);

        // make primary using the INTERNAL id even though a mapping exists
        JsonObject mp = makePrimary(process, primaryUser.getSupertokensUserId());
        assertEquals("OK", mp.get("status").getAsString());
        assertFalse(mp.get("wasAlreadyAPrimaryUser").getAsBoolean());

        // link using INTERNAL recipe id + EXTERNAL primary id
        JsonObject response = link(process, recipeUser.getSupertokensUserId(), "ext4");
        assertEquals("OK", response.get("status").getAsString());
        assertFalse(response.get("accountsAlreadyLinked").getAsBoolean());

        JsonObject jsonUser = response.get("user").getAsJsonObject();
        assertEquals(2, jsonUser.get("loginMethods").getAsJsonArray().size());

        process.kill();
        assertNotNull(process.checkOrWaitForEvent(ProcessState.PROCESS_STATE.STOPPED));
    }

    // The internal (SuperTokens) id must keep working on make-primary and link even after a
    // mapping has been created for the user. Existing tests cover make-primary by internal id
    // after a mapping, but not link by internal ids while mappings exist — this covers that.
    @Test
    public void testInternalIdsKeepWorkingAfterMappingCreated() throws Exception {
        TestingProcessManager.TestingProcess process = startProcess();
        if (StorageLayer.getStorage(process.getProcess()).getType() != STORAGE_TYPE.SQL) {
            return;
        }

        AuthRecipeUserInfo recipeUser = EmailPassword.signUp(process.getProcess(), "test5@example.com", "abcd1234");
        UserIdMapping.createUserIdMapping(process.getProcess(), recipeUser.getSupertokensUserId(), "ext5", null, false);

        AuthRecipeUserInfo primaryUser = EmailPassword.signUp(process.getProcess(), "test6@example.com", "abcd1234");
        UserIdMapping.createUserIdMapping(process.getProcess(), primaryUser.getSupertokensUserId(), "ext6", null, false);

        // make primary using the INTERNAL id while a mapping exists
        JsonObject mp = makePrimary(process, primaryUser.getSupertokensUserId());
        assertEquals("OK", mp.get("status").getAsString());
        assertFalse(mp.get("wasAlreadyAPrimaryUser").getAsBoolean());

        // link using BOTH INTERNAL ids while mappings exist for both users
        JsonObject response = link(process, recipeUser.getSupertokensUserId(), primaryUser.getSupertokensUserId());
        assertEquals("OK", response.get("status").getAsString());
        assertFalse(response.get("accountsAlreadyLinked").getAsBoolean());

        JsonObject jsonUser = response.get("user").getAsJsonObject();
        assertEquals(2, jsonUser.get("loginMethods").getAsJsonArray().size());

        process.kill();
        assertNotNull(process.checkOrWaitForEvent(ProcessState.PROCESS_STATE.STOPPED));
    }
}
