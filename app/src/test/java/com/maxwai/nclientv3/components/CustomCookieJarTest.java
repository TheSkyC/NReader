// NReader modifications by TheSkyC (2026); see NOTICE.
package com.maxwai.nclientv3.components;

import org.junit.Test;

import okhttp3.Cookie;
import okhttp3.HttpUrl;

import static org.junit.Assert.assertFalse;
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
}
