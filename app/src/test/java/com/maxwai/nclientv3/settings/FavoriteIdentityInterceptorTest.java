// NReader modifications by TheSkyC (2026); see NOTICE.
package com.maxwai.nclientv3.settings;

import android.app.Application;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class FavoriteIdentityInterceptorTest {
    private Application application;

    @Before public void setUp() {
        application = RuntimeEnvironment.getApplication();
        Global.initFromShared(application);
        AuthStore.saveApiKey(application, "first-test-key", true);
    }

    private Request request() {
        return new Request.Builder().url("https://" + Global.getMirror() + "/api/v2/galleries/42/favorite")
            .tag(ApiAuthInterceptor.ExpectedIdentity.class,
                new ApiAuthInterceptor.ExpectedIdentity(ApiAuthInterceptor.fingerprint("first-test-key")))
            .build();
    }

    @Test public void rejectsChangedIdentityBeforeSendingRequest() throws Exception {
        Request request = request();
        AuthStore.saveApiKey(application, "second-test-key", true);
        AtomicBoolean reachedServer = new AtomicBoolean();
        OkHttpClient client = new OkHttpClient.Builder().addInterceptor(new ApiAuthInterceptor(application))
            .addInterceptor(chain -> {
                reachedServer.set(true);
                throw new IOException("Unexpected request");
            }).build();
        try {
            client.newCall(request).execute();
            fail("Changed identity must be rejected");
        } catch (IOException expected) {
            assertFalse(reachedServer.get());
        }
    }

    @Test public void oldAccountRejectionDoesNotInvalidateNewAccount() throws Exception {
        OkHttpClient client = new OkHttpClient.Builder().addInterceptor(new ApiAuthInterceptor(application))
            .addInterceptor(chain -> {
                assertEquals("Key first-test-key", chain.request().header("Authorization"));
                AuthStore.saveApiKey(application, "second-test-key", true);
                return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(403).message("Forbidden").body(ResponseBody.create("", null)).build();
            }).build();
        try (Response ignored = client.newCall(request()).execute()) {
            assertTrue(AuthStore.hasValidApiKey(application));
            assertEquals("second-test-key", AuthStore.getApiKey(application));
        }
    }
}
