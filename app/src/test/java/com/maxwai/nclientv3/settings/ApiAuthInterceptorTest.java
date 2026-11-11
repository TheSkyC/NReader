// NReader modifications by TheSkyC (2026); see NOTICE.
package com.maxwai.nclientv3.settings;

import org.junit.Test;

import okhttp3.HttpUrl;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ApiAuthInterceptorTest {
    @Test
    public void acceptsOnlyTrustedHttpsApiOrigin() {
        assertTrue(ApiAuthInterceptor.isTrustedApiRequest(
            HttpUrl.get("https://nhentai.net/api/v2/galleries/1"), "nhentai.net"));
        assertFalse(ApiAuthInterceptor.isTrustedApiRequest(
            HttpUrl.get("https://cdn.nhentai.net/api/v2/galleries/1"), "nhentai.net"));
        assertFalse(ApiAuthInterceptor.isTrustedApiRequest(
            HttpUrl.get("http://nhentai.net/api/v2/galleries/1"), "nhentai.net"));
        assertFalse(ApiAuthInterceptor.isTrustedApiRequest(
            HttpUrl.get("https://nhentai.net:8443/api/v2/galleries/1"), "nhentai.net"));
        assertFalse(ApiAuthInterceptor.isTrustedApiRequest(
            HttpUrl.get("https://nhentai.net/g/1"), "nhentai.net"));
    }
}
