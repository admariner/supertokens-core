package io.supertokens.test.totp.api;

import com.google.gson.JsonObject;

import io.supertokens.ProcessState;
import io.supertokens.featureflag.EE_FEATURES;
import io.supertokens.featureflag.FeatureFlag;
import io.supertokens.featureflag.FeatureFlagTestContent;
import io.supertokens.test.httpRequest.HttpResponseException;
import io.supertokens.test.totp.TOTPRecipeTest;
import io.supertokens.pluginInterface.STORAGE_TYPE;
import io.supertokens.pluginInterface.totp.TOTPDevice;
import io.supertokens.storageLayer.StorageLayer;
import io.supertokens.test.TestingProcessManager;
import io.supertokens.test.Utils;
import io.supertokens.test.httpRequest.HttpRequestForTesting;
import io.supertokens.test.totp.TotpLicenseTest;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestRule;

import java.io.IOException;

import static org.junit.Assert.*;

public class VerifyTotpDeviceAPITest {

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

    private Exception updateDeviceRequest(TestingProcessManager.TestingProcess process, JsonObject body) {
        return assertThrows(
                io.supertokens.test.httpRequest.HttpResponseException.class,
                () -> HttpRequestForTesting.sendJsonPOSTRequest(
                        process.getProcess(),
                        "",
                        "http://localhost:3567/recipe/totp/device/verify",
                        body,
                        1000,
                        1000,
                        null,
                        Utils.getCdiVersionStringLatestForTests(),
                        "totp"));
    }

    private void requestWithInvalidCode(TestingProcessManager.TestingProcess process, JsonObject body)
            throws HttpResponseException, IOException {
        JsonObject resp = HttpRequestForTesting.sendJsonPOSTRequest(
                process.getProcess(),
                "",
                "http://localhost:3567/recipe/totp/device/verify",
                body,
                1000,
                1000,
                null,
                Utils.getCdiVersionStringLatestForTests(),
                "totp");
        assertEquals("INVALID_TOTP_ERROR", resp.get("status").getAsString());
    }

    private void checkFieldMissingErrorResponse(Exception ex, String fieldName) {
        assert ex instanceof HttpResponseException;
        HttpResponseException e = (HttpResponseException) ex;
        assert e.statusCode == 400;
        assertTrue(e.getMessage().contains(
                "Http error. Status Code: 400. Message: Field name '" + fieldName + "' is invalid in JSON input"));
    }

    private void checkResponseErrorContains(Exception ex, String msg) {
        assert ex instanceof HttpResponseException;
        HttpResponseException e = (HttpResponseException) ex;
        assert e.statusCode == 400;
        assertTrue(e.getMessage().contains(msg));
    }

    @Test
    public void testApi() throws Exception {
        String[] args = {"../"};

        // Trigger rate limiting on 1 wrong attempts:
        Utils.setValueInConfig("totp_max_attempts", "1");
        // Set cooldown to 1 second:
        Utils.setValueInConfig("totp_rate_limit_cooldown_sec", "1");

        TestingProcessManager.TestingProcess process = TestingProcessManager.start(args);
        assertNotNull(process.checkOrWaitForEvent(ProcessState.PROCESS_STATE.STARTED));

        if (StorageLayer.getStorage(process.getProcess()).getType() != STORAGE_TYPE.SQL) {
            return;
        }

        FeatureFlagTestContent.getInstance(process.getProcess())
                .setKeyValue(FeatureFlagTestContent.ENABLED_FEATURES, new EE_FEATURES[]{EE_FEATURES.MFA});

        // Setup user and devices:
        JsonObject createDeviceReq = new JsonObject();
        createDeviceReq.addProperty("userId", "user-id");
        createDeviceReq.addProperty("deviceName", "deviceName");
        createDeviceReq.addProperty("period", 30);
        createDeviceReq.addProperty("skew", 0);

        JsonObject createDeviceRes = HttpRequestForTesting.sendJsonPOSTRequest(
                process.getProcess(),
                "",
                "http://localhost:3567/recipe/totp/device",
                createDeviceReq,
                1000,
                1000,
                null,
                Utils.getCdiVersionStringLatestForTests(),
                "totp");
        assertEquals(createDeviceRes.get("status").getAsString(), "OK");
        String secretKey = createDeviceRes.get("secret").getAsString();

        TOTPDevice device = new TOTPDevice("user-id", "deviceName", secretKey, 30, 0, false,
                System.currentTimeMillis());

        // Start the actual tests for update device API:

        JsonObject body = new JsonObject();

        // Missing userId/deviceName/skew/period
        {
            Exception e = updateDeviceRequest(process, body);
            checkFieldMissingErrorResponse(e, "userId");

            body.addProperty("userId", "");
            e = updateDeviceRequest(process, body);
            checkFieldMissingErrorResponse(e, "deviceName");

            body.addProperty("deviceName", "");
            e = updateDeviceRequest(process, body);
            checkFieldMissingErrorResponse(e, "totp");
        }

        // Invalid userId/deviceName/totp
        {
            body.addProperty("totp", "");
            Exception e = updateDeviceRequest(process, body);
            checkResponseErrorContains(e, "userId cannot be empty"); // Note that this is not a field missing error

            body.addProperty("userId", device.userId);
            e = updateDeviceRequest(process, body);
            checkResponseErrorContains(e, "deviceName cannot be empty");

            body.addProperty("deviceName", device.deviceName);
            requestWithInvalidCode(process, body);

            Thread.sleep(1100);

            // test totp of length 5:
            body.addProperty("totp", "12345");
            requestWithInvalidCode(process, body);

            Thread.sleep(1100);

            // test totp of length 8:
            body.addProperty("totp", "12345678");
            requestWithInvalidCode(process, body);

            Thread.sleep(1100);

            // test totp of more than length 8:
            body.addProperty("totp", "123456781234");
            requestWithInvalidCode(process, body);

            Thread.sleep(1100);

            // test totp of length alphabets:
            body.addProperty("totp", "abcd");
            requestWithInvalidCode(process, body);

            Thread.sleep(2100);

            // but let's pass invalid code first
            body.addProperty("totp", "123456");
            JsonObject res0 = HttpRequestForTesting.sendJsonPOSTRequest(
                    process.getProcess(),
                    "",
                    "http://localhost:3567/recipe/totp/device/verify",
                    body,
                    1000,
                    1000,
                    null,
                    Utils.getCdiVersionStringLatestForTests(),
                    "totp");
            assertEquals(3, res0.entrySet().size());
            assert res0.get("status").getAsString().equals("INVALID_TOTP_ERROR");
            assertEquals(1, res0.get("currentNumberOfFailedAttempts").getAsInt());
            assertEquals(1, res0.get("maxNumberOfFailedAttempts").getAsInt());

            // Check that rate limiting is triggered for the user:
            JsonObject res3 = HttpRequestForTesting.sendJsonPOSTRequest(
                    process.getProcess(),
                    "",
                    "http://localhost:3567/recipe/totp/device/verify",
                    body,
                    1000,
                    1000,
                    null,
                    Utils.getCdiVersionStringLatestForTests(),
                    "totp");
            assert res3.get("status").getAsString().equals("LIMIT_REACHED_ERROR");
            assert res3.get("retryAfterMs") != null;

            // wait for cooldown to end (1s)
            Thread.sleep(1000);

            // should pass now on valid code
            String validTotp = TOTPRecipeTest.generateTotpCode(process.getProcess(), device);
            body.addProperty("totp", validTotp);
            JsonObject res = HttpRequestForTesting.sendJsonPOSTRequest(
                    process.getProcess(),
                    "",
                    "http://localhost:3567/recipe/totp/device/verify",
                    body,
                    1000,
                    1000,
                    null,
                    Utils.getCdiVersionStringLatestForTests(),
                    "totp");
            assert res.get("status").getAsString().equals("OK");
            assert res.get("wasAlreadyVerified").getAsBoolean() == false;

            // try again to verify the user with any code (valid/invalid)
            body.addProperty("totp", "mycode");
            JsonObject res2 = HttpRequestForTesting.sendJsonPOSTRequest(
                    process.getProcess(),
                    "",
                    "http://localhost:3567/recipe/totp/device/verify",
                    body,
                    1000,
                    1000,
                    null,
                    Utils.getCdiVersionStringLatestForTests(),
                    "totp");
            assert res2.get("status").getAsString().equals("OK");
            assert res2.get("wasAlreadyVerified").getAsBoolean() == true;

            // try again with unknown device
            body.addProperty("deviceName", "non-existent-device");
            JsonObject res4 = HttpRequestForTesting.sendJsonPOSTRequest(
                    process.getProcess(),
                    "",
                    "http://localhost:3567/recipe/totp/device/verify",
                    body,
                    1000,
                    1000,
                    null,
                    Utils.getCdiVersionStringLatestForTests(),
                    "totp");
            assert res4.get("status").getAsString().equals("UNKNOWN_DEVICE_ERROR");

            // try verifying device for a non-existent user
            body.addProperty("userId", "non-existent-user");
            JsonObject res5 = HttpRequestForTesting.sendJsonPOSTRequest(
                    process.getProcess(),
                    "",
                    "http://localhost:3567/recipe/totp/device/verify",
                    body,
                    1000,
                    1000,
                    null,
                    Utils.getCdiVersionStringLatestForTests(),
                    "totp");
            assert res5.get("status").getAsString().equals("UNKNOWN_DEVICE_ERROR");
        }

        process.kill();
        assertNotNull(process.checkOrWaitForEvent(ProcessState.PROCESS_STATE.STOPPED));
    }
}
