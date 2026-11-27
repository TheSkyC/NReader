package com.theskyc.nreader.components.reader;

import android.net.Uri;

import com.theskyc.nreader.settings.Global;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class ReaderImageRequestPolicyTest {
    @Test public void disallowedNetworkPolicyUsesCacheOnlyForRemotePages() {
        assertTrue(ReaderImageRequestPolicy.onlyUseCache(
            Uri.parse("https://i.nhentai.net/galleries/1/1.jpg"), Global.DataUsageType.NONE));
        assertTrue(ReaderImageRequestPolicy.onlyUseCache(
            Uri.parse("http://i.nhentai.net/galleries/1/1.jpg"), Global.DataUsageType.NONE));
        assertFalse(ReaderImageRequestPolicy.onlyUseCache(
            Uri.parse("https://i.nhentai.net/galleries/1/1.jpg"), Global.DataUsageType.FULL));
        assertFalse(ReaderImageRequestPolicy.onlyUseCache(
            Uri.parse("https://i.nhentai.net/galleries/1/1.jpg"), Global.DataUsageType.THUMBNAIL));
        assertFalse(ReaderImageRequestPolicy.onlyUseCache(
            Uri.fromFile(new java.io.File("/tmp/page.jpg")), Global.DataUsageType.NONE));
        assertFalse(ReaderImageRequestPolicy.onlyUseCache(null, Global.DataUsageType.NONE));
    }
}
