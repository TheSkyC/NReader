package com.theskyc.nreader;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.text.Spanned;
import android.text.style.URLSpan;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import com.theskyc.nreader.async.TagTranslationFeed;
import com.theskyc.nreader.async.database.TagTranslationManifest;
import com.theskyc.nreader.settings.Global;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.util.ReflectionHelpers;

import java.nio.charset.StandardCharsets;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class, qualifiers = "en-rUS")
public class TranslationAttributionTest {
    private static final String DIGEST = "49df957e214c4431f37b14444a52dc924777423f89bd2403005e2e2f4c8c1d64";
    private static final String DIGEST_REVISION_2 = "26e1672781f481fc135dad920766441b7efd53c75295088991c403d3cb9df40f";

    @Test public void onlyKnownPayloadShowsAttributionAndLinks() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        Global.initFromShared(app);
        try (ActivityController<SettingsActivity> controller = Robolectric.buildActivity(SettingsActivity.class).setup()) {
            SettingsActivity activity = controller.get();
            TagTranslationFeed feed = new TagTranslationFeed(app, "https://example.org/manifest.json");
            for (TagTranslationManifest.Pack known : new TagTranslationManifest.Pack[]{
                descriptor("zh-Hans", 1, DIGEST), descriptor("zh-Hans", 2, DIGEST_REVISION_2)
            }) {
                AlertDialog dialog = showConfirmation(activity, feed, known);
                CharSequence message = ((TextView) dialog.findViewById(android.R.id.message)).getText();
                assertTrue(message.toString().contains("EhTagTranslation"));
                assertTrue(message.toString().contains("CC BY-NC-SA"));
                assertEquals(2, ((Spanned) message).getSpans(0, message.length(), URLSpan.class).length);
                dialog.dismiss();
            }

            for (TagTranslationManifest.Pack other : new TagTranslationManifest.Pack[]{
                descriptor("zh-Hans", 1, "0".repeat(64)), descriptor("zh-Hans", 3, "1".repeat(64)),
                descriptor("ja", 1, DIGEST)
            }) {
                AlertDialog dialog = showConfirmation(activity, feed, other);
                String text = ((TextView) dialog.findViewById(android.R.id.message)).getText().toString();
                assertFalse(text.contains("EhTagTranslation"));
                dialog.dismiss();
            }
        }
    }

    private static AlertDialog showConfirmation(SettingsActivity activity, TagTranslationFeed feed,
                                                TagTranslationManifest.Pack pack) {
        ReflectionHelpers.callInstanceMethod(activity, "confirmDownloadTranslationPack",
            ReflectionHelpers.ClassParameter.from(TagTranslationFeed.class, feed),
            ReflectionHelpers.ClassParameter.from(TagTranslationManifest.Pack.class, pack));
        return (AlertDialog) ShadowDialog.getLatestDialog();
    }

    private static TagTranslationManifest.Pack descriptor(String locale, int revision, String digest) throws Exception {
        String manifest = "{\"schemaVersion\":1,\"source\":\"nreader-translations\","
            + "\"kind\":\"translations\",\"packs\":[{\"locale\":\"" + locale
            + "\",\"revision\":" + revision + ",\"sizeBytes\":1875565,\"sha256\":\"" + digest + "\"}]}";
        return TagTranslationManifest.parse(manifest.getBytes(StandardCharsets.UTF_8)).getPacks().get(locale);
    }
}
