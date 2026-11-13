package com.maxwai.nclientv3.loginapi;

import android.app.Application;
import android.util.JsonReader;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.IOException;
import java.io.StringReader;
import java.util.Arrays;

import okhttp3.ResponseBody;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class UserTest {
    @Test public void validProfileIsParsed() throws Exception {
        User user = new User(new JsonReader(new StringReader("{\"id\":42,\"username\":\"fixture\"}")));
        assertEquals(42, user.getId());
        assertEquals("fixture", user.getUsername());
    }

    @Test public void incompleteProfilesAreRejected() throws Exception {
        for (String json : new String[]{"{}", "{\"id\":0,\"username\":\"fixture\"}",
            "{\"id\":42,\"username\":\"  \"}"}) {
            try {
                new User(new JsonReader(new StringReader(json)));
                fail("Incomplete profile must be rejected");
            } catch (IOException expected) {
                // Expected.
            }
        }
    }

    @Test public void oversizedProfileIsRejectedBeforeParsing() throws Exception {
        char[] padding = new char[65537];
        Arrays.fill(padding, ' ');
        try (ResponseBody body = ResponseBody.create(new String(padding), null)) {
            try {
                User.readResponse(body);
                fail("Oversized profile must be rejected");
            } catch (IOException expected) {
                assertEquals("Account profile response too large", expected.getMessage());
            }
        }
    }

    @Test public void invalidIdentityTypesDuplicatesAndTrailingContentAreRejected() throws Exception {
        for (String json : new String[]{
            "{\"id\":\"42\",\"username\":\"fixture\"}",
            "{\"id\":42.0,\"username\":\"fixture\"}",
            "{\"id\":2147483648,\"username\":\"fixture\"}",
            "{\"id\":42,\"id\":43,\"username\":\"fixture\"}",
            "{\"id\":42,\"username\":42}",
            "{\"id\":42,\"username\":\"one\",\"username\":\"two\"}",
            "{\"id\":42,\"username\":\"fixture\"} {}"}) {
            try (ResponseBody body = ResponseBody.create(json, null)) {
                try {
                    User.readResponse(body);
                    fail("Invalid profile must be rejected");
                } catch (IOException expected) {
                    // Expected.
                }
            }
        }
    }
}
