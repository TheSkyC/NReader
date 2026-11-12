package com.maxwai.nclientv3.loginapi;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import android.app.Application;
import org.json.JSONObject;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class NhentaiLoginApiTest {
    @Test public void siteKeyCannotBreakOutOfScript() {
        assertTrue(NhentaiLoginApi.validSiteKey("0x4AAAAAABmfxjJLaRcDE3KW"));
        assertFalse(NhentaiLoginApi.validSiteKey("</script><script>alert(1)</script>"));
        assertFalse(NhentaiLoginApi.validSiteKey("key\";alert(1)"));
        assertFalse(NhentaiLoginApi.validSiteKey(""));
    }

    @Test public void loginResponseRequiresUserAndStringTokens() throws Exception {
        JSONObject valid = new JSONObject("{\"success\":true,\"user\":{},\"access_token\":\"access-fixture\",\"refresh_token\":\"refresh-fixture\"}");
        assertEquals(2, NhentaiLoginApi.parseTokens(valid).size());
        assertThrows(java.io.IOException.class, () -> NhentaiLoginApi.parseTokens(
            new JSONObject("{\"success\":false,\"user\":{},\"access_token\":\"access-fixture\",\"refresh_token\":\"refresh-fixture\"}")));
        assertThrows(java.io.IOException.class, () -> NhentaiLoginApi.parseTokens(
            new JSONObject("{\"user\":{},\"access_token\":123,\"refresh_token\":\"refresh-fixture\"}")));
        assertThrows(java.io.IOException.class, () -> NhentaiLoginApi.parseTokens(
            new JSONObject("{\"access_token\":\"access-fixture\",\"refresh_token\":\"refresh-fixture\"}")));
        assertTrue(NhentaiLoginApi.parseTokens(new JSONObject(
            "{\"user\":{},\"access_token\":\"\",\"refresh_token\":\"refresh-fixture\"}")).isEmpty());
    }
}
