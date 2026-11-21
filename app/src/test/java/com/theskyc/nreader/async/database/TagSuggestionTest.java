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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
        TagTranslationCatalog.createTables(database);
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

        assertEquals(List.of("prefix%literal"), names(Queries.TagTable.getTagSuggestions(TagType.TAG, "prefix%", (String) null)));
        assertEquals(List.of("prefixXliteral"), names(Queries.TagTable.getTagSuggestions(TagType.TAG, "prefixx", (String) null)));
    }

    @Test public void suggestionsAreBoundedAndUseTheTypeNameIndex() {
        for (int id = 1; id <= 65; id++) insert(id, String.format("suggestion%03d", id), TagType.TAG);

        List<String> suggestions = names(Queries.TagTable.getTagSuggestions(TagType.TAG, "suggestion", (String) null));
        assertEquals(50, suggestions.size());
        assertEquals("suggestion001", suggestions.get(0));
        assertEquals("suggestion050", suggestions.get(49));

        try (Cursor plan = database.rawQuery("EXPLAIN QUERY PLAN "
            + Queries.TagTable.buildTagSuggestionQuery("zh-Hans", true), new String[]{
                Integer.toString(TagType.TAG.getId()), "0", "suggestion%", "zh-Hans", "suggestion", "suggestioo",
                "zh-Hans", "suggestion", "suggestioo", "zh-Hans", Integer.toString(TagType.TAG.getId()), "0"
            })) {
            boolean usesTagIndex = false;
            boolean boundsTranslationPrefix = false;
            boolean boundsAliasPrefix = false;
            while (plan.moveToNext()) {
                String detail = plan.getString(plan.getColumnIndexOrThrow("detail"));
                usesTagIndex |= detail.contains("Tags_type_name_suggestion_idx");
                boundsTranslationPrefix |= detail.contains("TagTranslations_locale_key_idx")
                    && detail.contains("displayKey>?") && detail.contains("displayKey<?");
                boundsAliasPrefix |= detail.contains("TagTranslationAliases_locale_key_idx")
                    && detail.contains("aliasKey>?") && detail.contains("aliasKey<?");
            }
            assertTrue(usesTagIndex);
            assertTrue(boundsTranslationPrefix);
            assertTrue(boundsAliasPrefix);
        }
    }

    @Test public void translatedPrefixRangesMatchLiteralWildcardsAndUnicode() throws IOException {
        insert(1, "first", TagType.TAG);
        insert(2, "second", TagType.TAG);
        String lastCodePoint = new String(Character.toChars(Character.MAX_CODE_POINT));
        assertTrue(TagTranslationCatalog.apply(database,
            new ByteArrayInputStream(("{\"schemaVersion\":1,\"locale\":\"ja\",\"revision\":1,"
                + "\"entries\":[{\"id\":1,\"name\":\"prefix%literal\",\"aliases\":[\"prefix_%alias\","
                + "\"Caf\u00e9\uD83D\uDE00\",\"" + lastCodePoint + "alias\",\"x" + lastCodePoint + "alias\"]},"
                + "{\"id\":2,\"name\":\"prefixXliteral\",\"aliases\":[\"prefixXalias\",\"cafeteria\",\"y\"]}]}")
                .getBytes(StandardCharsets.UTF_8)), () -> false));

        assertEquals(List.of("first"), names(Queries.TagTable.getTagSuggestions(TagType.TAG, "prefix%", "ja")));
        assertEquals(List.of("first"), names(Queries.TagTable.getTagSuggestions(TagType.TAG, "prefix_%", "ja")));
        assertEquals(List.of("first"), names(Queries.TagTable.getTagSuggestions(TagType.TAG, "CAFE\u0301\uD83D\uDE00", "ja")));
        assertEquals(List.of("first"), names(Queries.TagTable.getTagSuggestions(TagType.TAG, lastCodePoint, "ja")));
        assertEquals(List.of("first"), names(Queries.TagTable.getTagSuggestions(TagType.TAG, "x" + lastCodePoint, "ja")));
        assertEquals(List.of("first", "second"), names(Queries.TagTable.getTagSuggestions(TagType.TAG, "", "ja")));
    }

    private List<String> names(List<com.theskyc.nreader.api.components.TagSuggestion> suggestions) {
        return suggestions.stream().map(suggestion -> suggestion.getTag().getName()).toList();
    }

    private void insert(int id, String name, TagType type) {
        database.execSQL("INSERT INTO Tags (idTag, name, type, count) VALUES (?, ?, ?, 100)",
            new Object[]{id, name, type.getId()});
    }
}
