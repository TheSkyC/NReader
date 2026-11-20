package com.theskyc.nreader.async.database;

import android.app.Application;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import com.theskyc.nreader.api.enums.TagType;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class TagSuggestionTest {
    private SQLiteDatabase database;

    @Before public void openDatabase() {
        database = SQLiteDatabase.create(null);
        Queries.TagTable.createTables(database);
        Queries.setDb(database);
    }

    @After public void closeDatabase() {
        Queries.setDb(null);
        database.close();
    }

    @Test public void suggestionsEscapeLikeWildcardsAndMatchOnlyTheirType() {
        insert(1, "prefix%literal", TagType.TAG);
        insert(2, "prefixXliteral", TagType.TAG);
        insert(3, "prefix%other", TagType.ARTIST);

        assertEquals(List.of("prefix%literal"), Queries.TagTable.getTagNameSuggestions(TagType.TAG, "prefix%"));
        assertEquals(List.of("prefixXliteral"), Queries.TagTable.getTagNameSuggestions(TagType.TAG, "prefixx"));
    }

    @Test public void suggestionsAreBoundedAndUseTheTypeNameIndex() {
        for (int id = 1; id <= 65; id++) insert(id, String.format("suggestion%03d", id), TagType.TAG);

        List<String> suggestions = Queries.TagTable.getTagNameSuggestions(TagType.TAG, "suggestion");
        assertEquals(50, suggestions.size());
        assertEquals("suggestion001", suggestions.get(0));
        assertEquals("suggestion050", suggestions.get(49));

        try (Cursor plan = database.rawQuery("EXPLAIN QUERY PLAN SELECT name FROM Tags"
            + " WHERE type = ? AND count >= ? AND name COLLATE NOCASE LIKE ? ESCAPE '\\'"
            + " ORDER BY name COLLATE NOCASE, idTag LIMIT 50", new String[]{
                Integer.toString(TagType.TAG.getId()), "0", "suggestion%"
            })) {
            boolean usesSuggestionIndex = false;
            while (plan.moveToNext()) {
                if (plan.getString(plan.getColumnIndexOrThrow("detail")).contains("Tags_type_name_suggestion_idx")) {
                    usesSuggestionIndex = true;
                    break;
                }
            }
            assertTrue(usesSuggestionIndex);
        }
    }

    private void insert(int id, String name, TagType type) {
        database.execSQL("INSERT INTO Tags (idTag, name, type, count) VALUES (?, ?, ?, 100)",
            new Object[]{id, name, type.getId()});
    }
}
