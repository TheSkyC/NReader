// NReader modifications by TheSkyC (2026); see NOTICE.
package com.maxwai.nclientv3.components;

import org.junit.Test;

import com.franmontiel.persistentcookiejar.cache.SetCookieCache;
import com.franmontiel.persistentcookiejar.persistence.CookiePersistor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import okhttp3.Cookie;
import okhttp3.HttpUrl;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class CustomCookieJarTest {
    private static final HttpUrl SITE = HttpUrl.get("https://nhentai.net/");

    @Test
    public void onlyMatchingDomainPathAndSchemeCookiesAreEligible() {
        Cookie siteCookie = Cookie.parse(SITE, "session=site; Path=/; Domain=nhentai.net; Secure");
        Cookie otherHostCookie = Cookie.parse(HttpUrl.get("https://example.com/"), "session=other; Path=/; Domain=example.com; Secure");
        Cookie nestedPathCookie = Cookie.parse(SITE, "session=nested; Path=/account; Domain=nhentai.net; Secure");

        assertTrue(CustomCookieJar.cookieMatches(siteCookie, HttpUrl.get("https://nhentai.net/api/v2/user")));
        assertFalse(CustomCookieJar.cookieMatches(otherHostCookie, HttpUrl.get("https://nhentai.net/api/v2/user")));
        assertFalse(CustomCookieJar.cookieMatches(nestedPathCookie, HttpUrl.get("https://nhentai.net/api/v2/user")));
        assertFalse(CustomCookieJar.cookieMatches(siteCookie, HttpUrl.get("http://nhentai.net/api/v2/user")));
    }

    @Test
    public void apiRequestsExcludeStoredAccountCookiesButKeepProtectionCookies() {
        Cookie account = Cookie.parse(SITE, "access_token=fixture; Path=/; Secure");
        Cookie clearance = Cookie.parse(SITE, "cf_clearance=fixture; Path=/; Secure");
        Cookie botProtection = Cookie.parse(SITE, "__cf_bm=fixture; Path=/; Secure");
        List<Cookie> stored = new ArrayList<>(Arrays.asList(account, clearance, botProtection));
        CookiePersistor persistor = new CookiePersistor() {
            @Override public List<Cookie> loadAll() { return new ArrayList<>(stored); }
            @Override public void saveAll(Collection<Cookie> cookies) { stored.addAll(cookies); }
            @Override public void removeAll(Collection<Cookie> cookies) { stored.removeAll(cookies); }
            @Override public void clear() { stored.clear(); }
        };
        CustomCookieJar jar = new CustomCookieJar(new SetCookieCache(), persistor);

        List<Cookie> apiCookies = jar.loadForRequest(HttpUrl.get("https://nhentai.net/api/v2/galleries"));
        assertEquals(2, apiCookies.size());
        assertTrue(apiCookies.contains(clearance));
        assertTrue(apiCookies.contains(botProtection));
        assertFalse(apiCookies.contains(account));
        assertTrue(jar.loadForRequest(HttpUrl.get("https://nhentai.net/user/settings")).contains(account));
    }

    @Test
    public void mirrorApiUsesSamePolicyWithoutAffectingOtherHosts() {
        HttpUrl mirror = HttpUrl.get("https://mirror.example/api/v2/search");
        Cookie account = Cookie.parse(mirror, "session=fixture; Path=/; Secure");
        Cookie clearance = Cookie.parse(mirror, "cf_clearance=fixture; Path=/; Secure");
        assertFalse(CustomCookieJar.cookieMatches(account, mirror, "mirror.example"));
        assertTrue(CustomCookieJar.cookieMatches(clearance, mirror, "mirror.example"));
        assertTrue(CustomCookieJar.cookieMatches(account, mirror, "another.example"));
        assertTrue(CustomCookieJar.cookieMatches(account, HttpUrl.get("https://mirror.example/user"), "mirror.example"));
    }
}
