package com.theskyc.nreader.async.database;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.theskyc.nreader.api.enums.TagType;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Locale;

@RunWith(AndroidJUnit4.class)
public class TagTranslationScriptFallbackTest {
    @Test public void scriptPackSuppliesDisplayAndSearchWithoutCrossingScripts() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        try (SQLiteDatabase database = SQLiteDatabase.create(null)) {
            Queries.TagTable.createTables(database);
            TagTranslationCatalog.createTables(database);
            database.execSQL("INSERT INTO Tags (idTag, name, type, count) VALUES (1, 'canonical', 3, 10)");
            Queries.setDb(database);
            try {
                install(database, context, "sr", "generic", "generic-alias");
                install(database, context, "sr-Latn", "latin", "latin-alias");
                install(database, context, "sr-Cyrl", "cyrillic", "cyrillic-alias");
                Locale locale = Locale.forLanguageTag("sr-Latn-RS");
                assertEquals("sr-Latn", TagTranslationCatalog.resolveInstalledLocale(database, locale));
                assertEquals(Collections.singletonMap(1, "latin"), Queries.TagTable.getDisplayNames(
                    Collections.singletonList(1), locale));
                try (Cursor result = Queries.TagTable.getFilterCursor("latin-alias", TagType.TAG, false, true, locale)) {
                    assertTrue(result.moveToFirst());
                    assertEquals(1, result.getInt(result.getColumnIndexOrThrow("idTag")));
                    assertTrue(result.isLast());
                }
                TagTranslationCatalog.remove(database, "sr-Latn");
                assertEquals("sr", TagTranslationCatalog.resolveInstalledLocale(database, locale));
                TagTranslationCatalog.remove(database, "sr");
                assertNull(TagTranslationCatalog.resolveInstalledLocale(database, locale));
            } finally {
                Queries.setDb(null);
            }
        }
    }

    private void install(SQLiteDatabase database, Context context, String locale, String name, String alias)
        throws Exception {
        String pack = "{\"schemaVersion\":1,\"locale\":\"" + locale + "\",\"revision\":1,"
            + "\"entries\":[{\"id\":1,\"name\":\"" + name + "\",\"aliases\":[\"" + alias + "\"]}]}";
        assertTrue(TagTranslationCatalog.apply(database, new ByteArrayInputStream(pack.getBytes(StandardCharsets.UTF_8)),
            context.getCacheDir(), () -> false));
    }
}
