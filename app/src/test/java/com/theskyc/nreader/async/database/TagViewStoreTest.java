package com.theskyc.nreader.async.database;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.ContentValues;
import android.database.sqlite.SQLiteDatabase;

import com.theskyc.nreader.settings.Database;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class TagViewStoreTest {
    private SQLiteDatabase database;

    @Before
    public void openDatabase() {
        database = SQLiteDatabase.create(null);
        database.execSQL(TagViewStore.CREATE_TABLE);
        database.execSQL(Queries.StatusTable.CREATE_TABLE);
        Database.setDatabase(database);
    }

    @After
    public void closeDatabase() {
        Queries.setDb(null);
        database.close();
    }

    @Test
    public void listOrdersPinnedViewsFirstAndUpdatesRows() {
        ContentValues pinned = view("Pinned", 1, 1);
        ContentValues ordinary = view("Ordinary", 3, 0);
        long first = TagViewStore.insert(ordinary);
        long second = TagViewStore.insert(pinned);

        assertNotEquals(first, second);
        assertEquals("Pinned", TagViewStore.list().get(0).getAsString("name"));
        pinned.put("query", "artist:updated");
        assertTrue(TagViewStore.update(second, pinned));
        assertEquals("artist:updated", TagViewStore.list().get(0).getAsString("query"));
        assertTrue(TagViewStore.delete(first));
        assertFalse(TagViewStore.delete(first));
        assertEquals(1, TagViewStore.list().size());
    }

    @Test
    public void validationRejectsUnknownTypeAndMissingFilters() {
        ContentValues unknownType = view("Valid", 3, 0);
        unknownType.put("tag_type", 7);
        assertFalse(TagViewStore.isValid(unknownType, false));
        assertThrows(IllegalArgumentException.class, () -> TagViewStore.insert(unknownType));

        ContentValues missingFilter = view("Valid", 3, 0);
        missingFilter.remove("translated_only");
        assertFalse(TagViewStore.isValid(missingFilter, false));
    }

    private static ContentValues view(String name, int type, int pinned) {
        ContentValues values = new ContentValues();
        values.put("name", name);
        values.put("query", "artist:example");
        values.put("tag_type", type);
        values.put("min_count", TagViewStore.DEFAULT_MIN_COUNT);
        values.put("sort_by_name", 0);
        values.put("status_filter", -1);
        values.put("translated_only", 0);
        values.put("pinned", pinned);
        return values;
    }
}
