package com.theskyc.nreader.async;

import android.app.Application;
import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteDatabase;

import com.theskyc.nreader.api.enums.TagType;
import com.theskyc.nreader.async.database.DatabaseHelper;
import com.theskyc.nreader.async.database.Queries;
import com.theskyc.nreader.async.database.TagCatalog;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class TagCatalogSeedTest {
    @Test public void freshDatabaseCanSearchBundledCatalogWithoutNetwork() throws Exception {
        Application context = RuntimeEnvironment.getApplication();
        context.deleteDatabase("Entries.db");
        try (DatabaseHelper helper = new DatabaseHelper(context)) {
            SQLiteDatabase database = helper.getWritableDatabase();
            assertTrue(TagCatalogSeedWorker.install(database, context.getAssets(), () -> false));
            assertTrue(DatabaseUtils.longForQuery(database, "SELECT COUNT(*) FROM Tags", null)
                >= TagCatalog.MIN_DIRECTORY_ROWS);
            assertTrue(Queries.TagTable.getTagById(12227).getCount() > 0);
            assertNotNull(Queries.TagTable.searchTag("english", TagType.LANGUAGE));
            assertEquals(0, TagCatalog.installedRevision(database));
        }
        try (DatabaseHelper helper = new DatabaseHelper(context)) {
            assertFalse(TagCatalogSeedWorker.install(helper.getWritableDatabase(), context.getAssets(), () -> false));
            assertNotNull(Queries.TagTable.searchTag("english", TagType.LANGUAGE));
        } finally {
            Queries.setDb(null);
            context.deleteDatabase("Entries.db");
        }
    }
}
