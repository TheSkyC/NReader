// NReader modifications by TheSkyC (2026); see NOTICE.
package com.maxwai.nclientv3.async.database;

import android.app.Application;
import android.os.Looper;

import com.maxwai.nclientv3.components.classes.ReadingPosition;
import com.maxwai.nclientv3.files.LocalDownloadState;
import com.maxwai.nclientv3.settings.Global;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class GalleryStateRepositoryTest {
    private DatabaseHelper helper;
    private File root;

    @Before
    public void setUp() throws Exception {
        Application application = RuntimeEnvironment.getApplication();
        application.deleteDatabase("Entries.db");
        helper = new DatabaseHelper(application);
        helper.getWritableDatabase();
        root = Files.createTempDirectory(application.getCacheDir().toPath(), "state-test").toFile();
        Global.DOWNLOADFOLDER = root;
        GalleryStateRepository.invalidateLocalFiles();
    }

    @After
    public void tearDown() {
        helper.close();
        Global.recursiveDelete(root);
        Global.DOWNLOADFOLDER = null;
        GalleryStateRepository.invalidateLocalFiles();
    }

    @Test
    public void mutationsRefreshIndependentPersonalFacts() throws Exception {
        helper.getWritableDatabase().execSQL("INSERT INTO Favorite VALUES (123, 1)");
        helper.getWritableDatabase().execSQL("INSERT INTO Status VALUES ('Custom reading', 12345)");
        helper.getWritableDatabase().execSQL("INSERT INTO StatusManga VALUES (123, 'Custom reading', 1)");
        Queries.ReadingProgressTable.insert(123, new ReadingPosition(8, 0.25f));
        GalleryState first = load(123, root);
        assertTrue(first.favorite);
        assertEquals("Custom reading", first.statusName);
        assertEquals(8, first.position.page);
        assertEquals(LocalDownloadState.UNVERIFIED, first.local);
        Queries.FavoriteTable.removeFavorite(123);
        Queries.StatusMangaTable.remove(123);
        GalleryState second = load(123, root);
        assertFalse(second.favorite);
        assertNull(second.statusName);
        assertEquals(8, second.position.page);
    }

    @Test
    public void unavailableConfiguredStorageIsUnverified() throws Exception {
        Global.DOWNLOADFOLDER = new File(root, "unavailable-volume/Download");
        GalleryStateRepository.invalidateLocalFiles();
        assertEquals(LocalDownloadState.UNVERIFIED, load(123, null).local);
    }

    private GalleryState load(int id, File directory) throws Exception {
        AtomicReference<GalleryState> state = new AtomicReference<>();
        GalleryStateRepository.load(RuntimeEnvironment.getApplication(), id, directory, state::set);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (state.get() == null && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle();
            Thread.sleep(10);
        }
        assertNotNull("Expected gallery state callback", state.get());
        return state.get();
    }
}
