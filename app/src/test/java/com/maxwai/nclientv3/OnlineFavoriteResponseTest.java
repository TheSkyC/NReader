// NReader modifications by TheSkyC (2026); see NOTICE.
package com.maxwai.nclientv3;

import android.app.Application;

import com.maxwai.nclientv3.api.OnlineFavoriteClient;
import com.maxwai.nclientv3.settings.AuthStore;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class OnlineFavoriteResponseTest {
    @Test
    public void parsesFavoritedBooleanWithoutMatchingUnrelatedFields() {
        assertEquals(Boolean.TRUE, OnlineFavoriteClient.parseResponse("{\"favorited\":true}"));
        assertEquals(Boolean.FALSE, OnlineFavoriteClient.parseResponse(
            "{\"favorited\":false,\"other\":true}"));
    }

    @Test
    public void rejectsMissingWrongTypedMalformedAndEmptyResponses() {
        assertNull(OnlineFavoriteClient.parseResponse("{\"other\":true}"));
        assertNull(OnlineFavoriteClient.parseResponse("{\"favorited\":\"true\"}"));
        assertNull(OnlineFavoriteClient.parseResponse("not json"));
        assertNull(OnlineFavoriteClient.parseResponse(" "));
        assertNull(OnlineFavoriteClient.parseResponse(null));
    }

    @Test
    public void confirmedStateCacheIsSeparatedByValidatedApiKeyAndGallery() {
        Application application = org.robolectric.RuntimeEnvironment.getApplication();
        AuthStore.saveApiKey(application, "first-account-key", true);
        OnlineFavoriteClient.rememberState(application, 42, true);
        assertEquals(Boolean.TRUE, OnlineFavoriteClient.cachedState(application, 42));
        assertNull(OnlineFavoriteClient.cachedState(application, 43));

        AuthStore.saveApiKey(application, "second-account-key", true);
        assertNull(OnlineFavoriteClient.cachedState(application, 42));
        OnlineFavoriteClient.rememberState(application, 42, false);
        assertEquals(Boolean.FALSE, OnlineFavoriteClient.cachedState(application, 42));

        AuthStore.clear(application);
        assertNull(OnlineFavoriteClient.cachedState(application, 42));
    }
}
