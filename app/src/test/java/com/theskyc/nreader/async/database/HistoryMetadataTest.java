package com.theskyc.nreader.async.database;

import static org.junit.Assert.assertEquals;

import android.app.Application;

import com.theskyc.nreader.api.SimpleGallery;
import com.theskyc.nreader.api.enums.Language;
import com.theskyc.nreader.api.enums.SpecialTagIds;
import com.theskyc.nreader.api.enums.TagType;
import com.theskyc.nreader.settings.Global;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class HistoryMetadataTest {
    @Test
    public void historyRetainsCreditsWhenReopenedWithIncompleteMetadata() throws Exception {
        Application context = RuntimeEnvironment.getApplication();
        context.deleteDatabase("Entries.db");
        try (DatabaseHelper helper = new DatabaseHelper(context)) {
            helper.getWritableDatabase();
            Global.initFromShared(context);
            String base = "{\"id\":123,\"media_id\":\"456\",\"english_title\":\"Title\",\"thumbnail\":\"thumb.jpg\"";
            SimpleGallery complete = SimpleGallery.fromV2ListItem(context, new JSONObject(base
                + ",\"tags\":[{\"id\":7,\"name\":\"Artist, Name\",\"type\":\"artist\"},"
                + "{\"id\":" + SpecialTagIds.LANGUAGE_CHINESE + ",\"name\":\"chinese\",\"type\":\"language\"}]}"));
            Queries.HistoryTable.addGallery(complete);
            Queries.HistoryTable.addGallery(SimpleGallery.fromV2ListItem(context, new JSONObject(base + "}")));
            SimpleGallery restored = Queries.HistoryTable.getHistory().get(0);
            assertEquals(Language.CHINESE, restored.getLanguage());
            assertEquals("Artist, Name", restored.getTags().getTag(TagType.ARTIST, 0).getName());
        }
    }
}
