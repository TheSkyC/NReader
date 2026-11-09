// NReader modifications by TheSkyC (2026); see NOTICE.
package com.maxwai.nclientv3.adapters;

import android.app.Activity;
import android.app.Application;
import android.os.Looper;
import android.os.Parcel;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;

import com.maxwai.nclientv3.R;
import com.maxwai.nclientv3.api.SimpleGallery;
import com.maxwai.nclientv3.api.components.Gallery;
import com.maxwai.nclientv3.components.widgets.CustomGridLayoutManager;
import com.maxwai.nclientv3.api.local.LocalGallery;
import com.maxwai.nclientv3.async.database.DatabaseHelper;
import com.maxwai.nclientv3.async.database.GalleryStateRepository;
import com.maxwai.nclientv3.async.database.Queries;
import com.maxwai.nclientv3.components.classes.ReadingPosition;
import com.maxwai.nclientv3.settings.Global;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.json.JSONObject;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class GalleryStateBadgeTest {
    private ActivityController<Activity> controller;
    private Activity activity;
    private DatabaseHelper database;
    private File root;

    @Before
    public void setUp() throws Exception {
        controller = Robolectric.buildActivity(Activity.class);
        activity = controller.get();
        activity.setTheme(R.style.AppTheme);
        controller.setup();
        activity.deleteDatabase("Entries.db");
        database = new DatabaseHelper(activity);
        database.getWritableDatabase();
        Global.initFromShared(activity);
        root = Files.createTempDirectory(activity.getCacheDir().toPath(), "badge-test").toFile();
        Global.DOWNLOADFOLDER = root;
        GalleryStateRepository.invalidateLocalFiles();
    }

    @After
    public void tearDown() {
        controller.pause().stop().destroy();
        database.close();
        Global.recursiveDelete(root);
        Global.DOWNLOADFOLDER = null;
        GalleryStateRepository.invalidateLocalFiles();
    }

    @Test
    public void localProgressUsesMetadataTotalAndRebindingClearsPreviousFacts() throws Exception {
        for (int layout : new int[]{R.layout.entry_layout, R.layout.entry_layout_single}) {
            View card = LayoutInflater.from(activity).inflate(layout, null);
            activity.setContentView(card);
            GalleryStateBadge badge = new GalleryStateBadge(card);
            Queries.ReadingProgressTable.insert(111, new ReadingPosition(12, 0));
            Queries.ReadingProgressTable.insert(112, new ReadingPosition(3, 0));
            database.getWritableDatabase().execSQL("INSERT OR REPLACE INTO Favorite VALUES (111, 1)");
            GalleryStateRepository.invalidate();
            badge.bind(gallery(111, "{\"num_pages\":16}"));
            awaitProgress(card, "12/16");
            assertEquals(View.VISIBLE, card.findViewById(R.id.gallery_favorite).getVisibility());
            assertTrue(card.findViewById(R.id.gallery_favorite).isSelected());
            assertEquals(activity.getString(R.string.state_reading_page_total, 12, 16),
                card.findViewById(R.id.gallery_reading_progress).getContentDescription());

            badge.bind(gallery(112, "{}"));
            awaitProgress(card, "3/?");
            assertEquals(View.VISIBLE, card.findViewById(R.id.gallery_favorite).getVisibility());
            assertFalse(card.findViewById(R.id.gallery_favorite).isSelected());
            assertEquals(activity.getString(R.string.state_download_unverified),
                card.findViewById(R.id.gallery_local_files).getContentDescription());
            card.findViewById(R.id.gallery_local_files).performClick();
            assertNotNull(ShadowDialog.getLatestDialog());
            TextView message = ShadowDialog.getLatestDialog().findViewById(R.id.sheet_detail);
            assertEquals(activity.getString(R.string.state_download_unverified_help), message.getText().toString());
            ShadowDialog.getLatestDialog().dismiss();

            badge.bind(gallery(113, "{}"));
            awaitProgress(card, null);
            assertEquals(View.GONE, card.findViewById(R.id.gallery_reading_progress).getVisibility());
        }
    }

    private LocalGallery gallery(int id, String metadata) throws Exception {
        File directory = new File(root, Integer.toString(id));
        assertTrue(directory.isDirectory() || directory.mkdir());
        new File(directory, "." + id).createNewFile();
        Files.write(new File(directory, ".nomedia").toPath(), metadata.getBytes(StandardCharsets.UTF_8));
        // A local page count of one must not become the original total.
        Files.write(new File(directory, "001.png").toPath(), new byte[]{1, 2, 3});
        GalleryStateRepository.invalidateLocalFiles();
        return new LocalGallery(directory);
    }

    @Test
    public void narrowCardKeepsLargeProgressAndIconsInsideTheStateStrip() throws Exception {
        android.content.res.Configuration configuration = new android.content.res.Configuration(
            activity.getResources().getConfiguration());
        configuration.fontScale = 1.5f;
        activity.getResources().updateConfiguration(configuration, activity.getResources().getDisplayMetrics());
        View card = LayoutInflater.from(activity).inflate(R.layout.entry_layout, null);
        activity.setContentView(card);
        Queries.ReadingProgressTable.insert(114, new ReadingPosition(9999, 0));
        database.getWritableDatabase().execSQL("INSERT INTO Favorite VALUES (114, 1)");
        new GalleryStateBadge(card).bind(gallery(114, "{\"num_pages\":10000}"));
        awaitProgress(card, "9999/10000");
        int width = Math.round(104 * activity.getResources().getDisplayMetrics().density);
        card.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        card.layout(0, 0, card.getMeasuredWidth(), card.getMeasuredHeight());
        View strip = card.findViewById(R.id.gallery_state);
        for (int id : new int[]{R.id.gallery_favorite, R.id.gallery_local_files, R.id.gallery_reading_progress}) {
            View child = card.findViewById(id);
            assertTrue(child.getLeft() >= 0);
            assertTrue(child.getRight() <= strip.getWidth());
            assertTrue(child.getBottom() <= strip.getHeight());
        }
        TextView progress = card.findViewById(R.id.gallery_reading_progress);
        assertTrue(progress.getLayout().getHeight() <= progress.getHeight());
        View cover = card.findViewById(R.id.gallery_cover);
        assertTrue(card.findViewById(R.id.title).getTop() >= cover.getBottom());
        assertTrue(progress.getBottom() <= card.findViewById(R.id.gallery_favorite).getTop());
        assertTrue(progress.getRight() <= strip.getWidth());
        assertTrue(card.findViewById(R.id.gallery_favorite).getRight()
            <= card.findViewById(R.id.gallery_local_files).getLeft());
        assertEquals(activity.getString(R.string.state_reading_page_total, 9999, 10000), progress.getContentDescription());
    }

    @Test
    public void unreadCardShowsOriginalPageTotalWithoutChangingReadingPosition() throws Exception {
        View card = LayoutInflater.from(activity).inflate(R.layout.entry_layout, null);
        activity.setContentView(card);
        new GalleryStateBadge(card).bind(gallery(115, "{\"num_pages\":16}"));
        awaitProgress(card, activity.getString(R.string.page_count_format, 16));
        assertNull(Queries.ReadingProgressTable.fromId(115));
        TextView title = card.findViewById(R.id.title);
        assertEquals(2, title.getMaxLines());
    }

    @Test
    public void listCardPreservesOriginalTotalAcrossParcelTransfer() throws Exception {
        SimpleGallery gallery = SimpleGallery.fromV2ListItem(activity, new JSONObject(
            "{\"id\":116,\"media_id\":\"1\",\"english_title\":\"List test\",\"num_pages\":180}"));
        assertEquals(180, gallery.getPageCount());
        Parcel parcel = Parcel.obtain();
        try {
            gallery.writeToParcel(parcel, 0);
            parcel.setDataPosition(0);
            assertEquals(180, SimpleGallery.CREATOR.createFromParcel(parcel).getPageCount());
        } finally {
            parcel.recycle();
        }
        View card = LayoutInflater.from(activity).inflate(R.layout.entry_layout, null);
        activity.setContentView(card);
        new GalleryStateBadge(card).bind(gallery);
        awaitProgress(card, activity.getString(R.string.page_count_format, 180));
    }

    @Test
    public void favoriteMutationFinishesAfterItsCardIsRecycled() throws Exception {
        Gallery gallery = new Gallery(null, "{\"id\":117,\"num_pages\":1,\"title\":{\"pretty\":\"Favorite test\"},"
            + "\"cover\":{\"path\":\"galleries/1/cover.png\"},\"thumbnail\":{\"path\":\"galleries/1/thumb.png\"},"
            + "\"pages\":[{\"path\":\"galleries/1/1.png\",\"width\":1,\"height\":1}]}", null, false);
        Queries.GalleryTable.insert(gallery);
        View card = LayoutInflater.from(activity).inflate(R.layout.entry_layout, null);
        activity.setContentView(card);
        AtomicBoolean callback = new AtomicBoolean();
        GalleryCardActions.favorite(card, gallery.toSimpleGallery(), false, () -> false, () -> callback.set(true));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!Queries.FavoriteTable.isFavorite(117) && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle();
            Thread.sleep(10);
        }
        assertTrue(Queries.FavoriteTable.isFavorite(117));
        assertEquals(1, Queries.GalleryTable.galleryFromId(activity, 117).getPageCount());
        assertFalse(callback.get());
    }

    @Test
    public void removingFavoriteKeepsUndoAvailableAfterTheCardIsRecycled() throws Exception {
        Gallery gallery = new Gallery(null, "{\"id\":118,\"num_pages\":1,\"title\":{\"pretty\":\"Undo test\"},"
            + "\"cover\":{\"path\":\"galleries/1/cover.png\"},\"thumbnail\":{\"path\":\"galleries/1/thumb.png\"},"
            + "\"pages\":[{\"path\":\"galleries/1/1.png\",\"width\":1,\"height\":1}]}", null, false);
        Queries.FavoriteTable.addFavorite(gallery);
        View card = LayoutInflater.from(activity).inflate(R.layout.entry_layout, null);
        activity.setContentView(card);
        AtomicBoolean callback = new AtomicBoolean();
        GalleryCardActions.favorite(card, gallery, true, () -> false, () -> callback.set(true));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        View undo = null;
        while (System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle();
            undo = activity.findViewById(com.google.android.material.R.id.snackbar_action);
            if (!Queries.FavoriteTable.isFavorite(118) && undo != null) break;
            Thread.sleep(10);
        }
        assertFalse(Queries.FavoriteTable.isFavorite(118));
        assertNotNull(undo);
        undo.performClick();
        deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!Queries.FavoriteTable.isFavorite(118) && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle();
            Thread.sleep(10);
        }
        assertTrue(Queries.FavoriteTable.isFavorite(118));
        assertFalse(callback.get());
    }

    @Test
    public void galleryGridLimitsColumnsToKeepActionTargetsSeparate() {
        RecyclerView recycler = new RecyclerView(activity);
        CustomGridLayoutManager manager = new CustomGridLayoutManager(activity, 4);
        recycler.setLayoutManager(manager);
        recycler.setAdapter(new RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            @Override public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int type) {
                return new RecyclerView.ViewHolder(LayoutInflater.from(activity).inflate(R.layout.entry_layout, parent, false)) {};
            }
            @Override public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {}
            @Override public int getItemCount() { return 4; }
        });
        GenericAdapter.prepareGalleryGrid(recycler);
        activity.setContentView(recycler);
        float density = activity.getResources().getDisplayMetrics().density;
        for (int width : new int[]{390, 600}) {
            int pixels = Math.round(width * density);
            recycler.measure(View.MeasureSpec.makeMeasureSpec(pixels, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(Math.round(900 * density), View.MeasureSpec.EXACTLY));
            recycler.layout(0, 0, pixels, Math.round(900 * density));
            assertEquals(width == 390 ? 3 : 4, manager.getSpanCount());
            View card = recycler.getChildAt(0);
            assertTrue(card.findViewById(R.id.gallery_favorite).getRight()
                + Math.round(8 * density) <= card.findViewById(R.id.gallery_local_files).getLeft());
        }
    }

    private void awaitProgress(View card, String expected) throws Exception {
        TextView progress = card.findViewById(R.id.gallery_reading_progress);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle();
            if (card.findViewById(R.id.gallery_state).getVisibility() == View.VISIBLE
                && (expected == null ? progress.getVisibility() == View.GONE
                    : expected.contentEquals(progress.getText()))) return;
            Thread.sleep(10);
        }
        fail("Expected progress " + expected + ", got " + progress.getText());
    }
}
