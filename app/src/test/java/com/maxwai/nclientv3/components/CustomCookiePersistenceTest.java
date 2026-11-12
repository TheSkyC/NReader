package com.maxwai.nclientv3.components;

import android.app.Application;
import android.content.SharedPreferences;

import com.franmontiel.persistentcookiejar.cache.SetCookieCache;
import com.franmontiel.persistentcookiejar.persistence.SharedPrefsCookiePersistor;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.Arrays;
import java.util.Collections;

import okhttp3.Cookie;
import okhttp3.HttpUrl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class CustomCookiePersistenceTest {
    @Test public void sessionReplacementDoesNotRestoreOlderPersistentCookie() {
        SharedPreferences preferences = RuntimeEnvironment.getApplication().getSharedPreferences("cookie-test", 0);
        preferences.edit().clear().commit();
        SharedPrefsCookiePersistor persistor = new SharedPrefsCookiePersistor(preferences);
        HttpUrl url = HttpUrl.get("https://nhentai.net/");
        Cookie persistent = Cookie.parse(url, "cf_clearance=old; Path=/; Secure; Max-Age=3600");
        Cookie session = Cookie.parse(url, "cf_clearance=new; Path=/; Secure; Domain=nhentai.net");
        CustomCookieJar jar = new CustomCookieJar(new SetCookieCache(), persistor);
        jar.saveFromResponse(url, Collections.singletonList(persistent));
        jar.saveFromResponse(url, Collections.singletonList(session));
        assertEquals(Collections.singletonList(session), jar.loadForRequest(url));
        assertTrue(new CustomCookieJar(new SetCookieCache(), persistor).loadForRequest(url).isEmpty());

        jar.saveFromResponse(url, Arrays.asList(persistent, session));
        assertEquals(Collections.singletonList(session), jar.loadForRequest(url));
        assertTrue(new CustomCookieJar(new SetCookieCache(), persistor).loadForRequest(url).isEmpty());

        jar.saveFromResponse(url, Arrays.asList(session, persistent));
        assertEquals(Collections.singletonList(persistent),
            new CustomCookieJar(new SetCookieCache(), persistor).loadForRequest(url));
        jar.clear();
        assertTrue(new CustomCookieJar(new SetCookieCache(), persistor).loadForRequest(url).isEmpty());
    }
}
