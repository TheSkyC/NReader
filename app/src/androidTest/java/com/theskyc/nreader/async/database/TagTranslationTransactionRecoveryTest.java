package com.theskyc.nreader.async.database;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.theskyc.nreader.TagRecoveryProcess;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayInputStream;

@RunWith(AndroidJUnit4.class)
public class TagTranslationTransactionRecoveryTest {
    @Test
    public void holdsTranslationTransactionForExternalProcessStop() throws Exception {
        String token = TagRecoveryProcess.tokenForPhase("translation-transaction");
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        try (SQLiteDatabase database = TranslationRecoveryFixture.createBaseline(
                TranslationRecoveryFixture.databaseFile(context, token), context.getCacheDir())) {
            TagTranslationCatalog.apply(database, candidate(), context.getCacheDir(), () -> {
                if (database.inTransaction() && TagTranslationCatalog.installedRevision(database, "ja") == 2) {
                    TranslationRecoveryFixture.assertInstalled(database, 2, "after");
                    TagRecoveryProcess.awaitExternalStop(token);
                }
                return false;
            });
            fail("The new pack should still be inside its uncommitted transaction");
        }
    }

    @Test
    public void previousTranslationSurvivesProcessDeathAndCanBeUpdated() throws Exception {
        String token = TagRecoveryProcess.tokenForPhase("verify");
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertEquals("Process death stranded a prepared translation",
            0, context.getCacheDir().list((dir, name) -> name.startsWith("tag-translation-") && name.endsWith(".tmp")).length);
        try (SQLiteDatabase database = TranslationRecoveryFixture.reopen(
                TranslationRecoveryFixture.databaseFile(context, token))) {
            TranslationRecoveryFixture.assertInstalled(database, 1, "before");
            assertTrue(TagTranslationCatalog.apply(database, candidate(), context.getCacheDir(), () -> false));
            TranslationRecoveryFixture.assertInstalled(database, 2, "after");
        }
    }

    private static ByteArrayInputStream candidate() {
        return new ByteArrayInputStream(TranslationRecoveryFixture.payload(2, "after"));
    }
}
