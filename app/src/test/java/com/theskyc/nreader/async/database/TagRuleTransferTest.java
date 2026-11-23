package com.theskyc.nreader.async.database;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertThrows;

import android.app.Application;
import android.database.sqlite.SQLiteDatabase;

import com.theskyc.nreader.api.components.Tag;
import com.theskyc.nreader.api.enums.TagStatus;
import com.theskyc.nreader.api.enums.TagType;
import com.theskyc.nreader.settings.Global;
import com.theskyc.nreader.settings.Login;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class TagRuleTransferTest {
    private DatabaseHelper helper;
    private SQLiteDatabase database;

    @Before
    public void openDatabase() {
        Application app = RuntimeEnvironment.getApplication();
        app.deleteDatabase(DatabaseHelper.DATABASE_NAME);
        Global.initFromShared(app);
        Login.initLogin(app);
        helper = new DatabaseHelper(app);
        database = helper.getWritableDatabase();
    }

    @After
    public void closeDatabase() {
        helper.close();
        Queries.setDb(null);
    }

    @Test
    public void exportIsDeterministicAndMergeRestoresRule() throws Exception {
        Queries.TagTable.insert(new Tag("fixture", 60, 9001, TagType.TAG, TagStatus.DEFAULT));
        database.execSQL("INSERT INTO TagRules (idTag, name, type, status) VALUES (9001, 'fixture', 3, 2)");
        String exported = TagRuleTransfer.exportJson(database);
        assertEquals("{\"schemaVersion\":1,\"rules\":[{\"idTag\":9001,\"type\":3,\"status\":2,\"name\":\"fixture\"}]}", exported);

        database.delete(Queries.TagTable.RULES_TABLE, null, null);
        TagRuleTransfer.Result result = TagRuleTransfer.apply(database,
            bytes(exported), TagRuleTransfer.Mode.MERGE);
        assertEquals(1, result.additions);
        try (android.database.Cursor cursor = database.rawQuery("SELECT status FROM TagRules WHERE idTag=9001", null)) {
            assertTrue(cursor.moveToFirst());
            assertEquals(2, cursor.getInt(0));
        }
    }

    @Test
    public void previewSeparatesAdditionUpdateAndTypeConflict() throws Exception {
        Queries.TagTable.insert(new Tag("existing", 60, 9002, TagType.TAG, TagStatus.DEFAULT));
        Queries.TagTable.insert(new Tag("conflict", 60, 9003, TagType.TAG, TagStatus.DEFAULT));
        database.execSQL("INSERT INTO TagRules (idTag, name, type, status) VALUES (9002, 'existing', 3, 2)");
        String json = "{\"schemaVersion\":1,\"rules\":["
            + "{\"idTag\":9002,\"type\":3,\"status\":2,\"name\":\"existing\"},"
            + "{\"idTag\":9004,\"type\":3,\"status\":1,\"name\":\"new\"},"
            + "{\"idTag\":9003,\"type\":4,\"status\":1,\"name\":\"conflict\"}]}";
        TagRuleTransfer.Preview preview = TagRuleTransfer.preview(database, bytes(json));
        assertEquals(3, preview.total);
        assertEquals(1, preview.additions);
        assertEquals(1, preview.unchanged);
        assertEquals(1, preview.conflicts);
        assertThrows(java.io.IOException.class, () ->
            TagRuleTransfer.apply(database, bytes(json), TagRuleTransfer.Mode.MERGE));
        assertEquals(1, count(database, "SELECT COUNT(*) FROM TagRules WHERE idTag=9002"));
        assertEquals(0, count(database, "SELECT COUNT(*) FROM TagRules WHERE idTag=9004"));
    }

    @Test
    public void duplicateIdentityAndUnknownFieldsAreRejected() {
        String duplicate = "{\"schemaVersion\":1,\"rules\":["
            + "{\"idTag\":9005,\"type\":3,\"status\":1,\"name\":\"x\"},"
            + "{\"idTag\":9005,\"type\":3,\"status\":2,\"name\":\"x\"}]}";
        assertThrows(java.io.IOException.class, () -> TagRuleTransfer.preview(database, bytes(duplicate)));
        String unknown = "{\"schemaVersion\":1,\"rules\":[],\"extra\":true}";
        assertThrows(java.io.IOException.class, () -> TagRuleTransfer.preview(database, bytes(unknown)));
    }

    private static ByteArrayInputStream bytes(String value) {
        return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
    }

    private static int count(SQLiteDatabase database, String sql) {
        try (android.database.Cursor cursor = database.rawQuery(sql, null)) {
            assertTrue(cursor.moveToFirst());
            return cursor.getInt(0);
        }
    }
}
