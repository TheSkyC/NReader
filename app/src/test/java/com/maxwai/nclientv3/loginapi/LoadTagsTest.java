package com.maxwai.nclientv3.loginapi;

import android.app.Application;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.IOException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class LoadTagsTest {
    private static final String TAG = "{\"id\":42,\"count\":3,\"name\":\"example\",\"type\":\"tag\"}";

    @Test public void parsesCompleteList() throws Exception {
        assertEquals(42, LoadTags.parse("{\"count\":1,\"tags\":[" + TAG + "]}").get(0).getId());
        assertEquals(0, LoadTags.parse("{\"count\":0,\"tags\":[]}").size());
    }

    @Test public void retainsLanguageAndCategoryEntries() throws Exception {
        String language = "{\"id\":43,\"count\":2,\"name\":\"english\",\"type\":\"language\"}";
        String category = "{\"id\":44,\"count\":1,\"name\":\"manga\",\"type\":\"category\"}";
        java.util.List<com.maxwai.nclientv3.api.components.Tag> tags =
            LoadTags.parse("{\"count\":2,\"tags\":[" + language + "," + category + "]}");
        assertEquals(com.maxwai.nclientv3.api.enums.TagType.LANGUAGE, tags.get(0).getType());
        assertEquals(com.maxwai.nclientv3.api.enums.TagType.CATEGORY, tags.get(1).getType());
    }

    @Test public void rejectsIncompleteOrInvalidLists() throws Exception {
        assertInvalid("{\"count\":2,\"tags\":[" + TAG + "]}");
        assertInvalid("{\"count\":2,\"tags\":[" + TAG + "," + TAG + "]}");
        assertInvalid("{\"count\":1,\"tags\":[{\"id\":42}]} ");
        assertInvalid("{\"count\":1,\"tags\":[" + TAG.replace("tag", "unknown") + "]}");
    }

    private static void assertInvalid(String json) throws Exception {
        try {
            LoadTags.parse(json);
            fail("Invalid blacklist response must be rejected");
        } catch (IOException expected) {
            // Expected.
        }
    }
}
