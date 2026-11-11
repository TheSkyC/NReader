// NReader modifications by TheSkyC (2026); see NOTICE.
package com.maxwai.nclientv3;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Intent;
import android.net.Uri;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class PINActivityTest {
    @Test
    public void forwardedLauncherIntentDropsTaskFlagsButKeepsSharePayload() {
        Intent source = new Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, "https://nhentai.net/g/123456/")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                | Intent.FLAG_GRANT_READ_URI_PERMISSION);

        Intent target = PINActivity.createMainIntent(source, RuntimeEnvironment.getApplication());

        assertEquals(MainActivity.class.getName(), target.getComponent().getClassName());
        assertEquals(Intent.ACTION_SEND, target.getAction());
        assertEquals("text/plain", target.getType());
        assertEquals("https://nhentai.net/g/123456/", target.getStringExtra(Intent.EXTRA_TEXT));
        assertTrue((target.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0);
        assertFalse((target.getFlags() & Intent.FLAG_ACTIVITY_NEW_TASK) != 0);
        assertFalse((target.getFlags() & Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED) != 0);
    }

    @Test
    public void forwardedGalleryLinkKeepsDataAndDoesNotCarryLauncherFlags() {
        Intent source = new Intent(Intent.ACTION_VIEW, Uri.parse("https://nhentai.net/g/123456/"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        Intent target = PINActivity.createMainIntent(source, RuntimeEnvironment.getApplication());

        assertEquals(Uri.parse("https://nhentai.net/g/123456/"), target.getData());
        assertEquals(0, target.getFlags());
    }
}
