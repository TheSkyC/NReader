package com.maxwai.nclientv3.loginapi;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class NhentaiLoginApiTest {
    @Test public void siteKeyCannotBreakOutOfScript() {
        assertTrue(NhentaiLoginApi.validSiteKey("0x4AAAAAABmfxjJLaRcDE3KW"));
        assertFalse(NhentaiLoginApi.validSiteKey("</script><script>alert(1)</script>"));
        assertFalse(NhentaiLoginApi.validSiteKey("key\";alert(1)"));
        assertFalse(NhentaiLoginApi.validSiteKey(""));
    }
}
