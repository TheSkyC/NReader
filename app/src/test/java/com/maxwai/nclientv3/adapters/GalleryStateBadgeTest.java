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
import com.maxwai.nclientv3.api.OnlineFavoriteClient;
import com.maxwai.nclientv3.api.components.Gallery;
import com.maxwai.nclientv3.api.components.Tag;
import com.maxwai.nclientv3.api.components.TagList;
import com.maxwai.nclientv3.api.enums.TagStatus;
import com.maxwai.nclientv3.api.enums.TagType;
import com.maxwai.nclientv3.components.widgets.CustomGridLayoutManager;
import com.maxwai.nclientv3.api.local.LocalGallery;
import com.maxwai.nclientv3.async.database.DatabaseHelper;
import com.maxwai.nclientv3.async.database.GalleryStateRepository;
import com.maxwai.nclientv3.async.database.Queries;
import com.maxwai.nclientv3.components.classes.ReadingPosition;
import com.maxwai.nclientv3.settings.AuthStore;
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
        AuthStore.clear(activity);
        Global.recursiveDelete(root);
        Global.DOWNLOADFOLDER = null;
        GalleryStateRepository.invalidateLocalFiles();
    }

    @Test
    public void creditsPreferArtistsAndClearGroupStateWhenRecycled() throws Exception {
        for (int layout : new int[]{R.layout.entry_layout, R.layout.entry_layout_single}) {
            View card = LayoutInflater.from(activity).inflate(layout, null);
            GalleryStateBadge badge = new GalleryStateBadge(card);
            TextView author = card.findViewById(R.id.gallery_author);
            SimpleGallery gallery = SimpleGallery.fromV2ListItem(null, new JSONObject(
                "{\"id\":119,\"media_id\":\"1\",\"english_title\":\"Credit test\"}"));
            TagList tags = gallery.getTags();
            tags.addTag(creditTag(1, TagType.GROUP, " Circle A "));
            tags.addTag(creditTag(2, TagType.GROUP, "Circle A"));
            tags.addTag(creditTag(3, TagType.GROUP, "Circle B"));
            tags.addTag(creditTag(4, TagType.ARTIST, " \t\n"));
            tags.addTag(creditTag(5, TagType.ARTIST, null));
            badge.bind(gallery);
            assertEquals("Circle A, Circle B", author.getText().toString());
            assertEquals(View.VISIBLE, author.getVisibility());
            assertNotNull(author.getCompoundDrawablesRelative()[0]);
            assertNull(author.getCompoundDrawablesRelative()[2]);
            assertEquals(activity.getString(R.string.cover_group_credit, "Circle A, Circle B"),
                author.getContentDescription());
            assertEquals(author.getContentDescription(), author.getTooltipText());
            int iconSize = Math.round(14 * activity.getResources().getDisplayMetrics().density);
            assertEquals(iconSize, author.getCompoundDrawablesRelative()[0].getBounds().width());

            tags.addTag(creditTag(6, TagType.ARTIST, " Artist A "));
            tags.addTag(creditTag(7, TagType.ARTIST, "Artist A"));
            tags.addTag(creditTag(8, TagType.ARTIST, "Artist B"));
            badge.bind(gallery);
            assertEquals("Artist A, Artist B", author.getText().toString());
            assertEquals(View.VISIBLE, author.getVisibility());
            assertNull(author.getCompoundDrawablesRelative()[0]);
            assertEquals("Artist A, Artist B", author.getContentDescription());
            assertEquals(author.getContentDescription(), author.getTooltipText());

            tags.retrieveForType(TagType.ARTIST).clear();
            badge.bind(gallery);
            assertNotNull(author.getCompoundDrawablesRelative()[0]);
            tags.retrieveForType(TagType.GROUP).clear();
            tags.addTag(creditTag(9, TagType.GROUP, " \t"));
            badge.bind(gallery);
            assertEmptyCredit(author);

            Parcel parcel = Parcel.obtain();
            try {
                gallery.writeToParcel(parcel, 0);
                parcel.setDataPosition(0);
                badge.bind(SimpleGallery.CREATOR.createFromParcel(parcel));
                assertEmptyCredit(author);
            } finally {
                parcel.recycle();
            }
        }
    }

    @Test
    public void localCreditsUseTheSameFallbackAndPreserveRowHeight() throws Exception {
        LocalGallery gallery = gallery(120, "{}");
        gallery.getGalleryData().getTags().addTag(creditTag(10, TagType.GROUP, "Local Circle"));
        View card = LayoutInflater.from(activity).inflate(R.layout.entry_layout, null);
        GalleryStateBadge badge = new GalleryStateBadge(card);
        TextView author = card.findViewById(R.id.gallery_author);
        badge.bind(gallery);
        assertEquals("Local Circle", author.getText().toString());
        assertNotNull(author.getCompoundDrawablesRelative()[0]);
        int width = Math.round(116 * activity.getResources().getDisplayMetrics().density);
        card.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int height = card.getMeasuredHeight();
        badge.bind(gallery(121, "{}"));
        assertEmptyCredit(author);
        card.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        assertEquals(height, card.getMeasuredHeight());
    }

    private static Tag creditTag(int id, TagType type, String name) {
        return new Tag(name, 0, id, type, TagStatus.DEFAULT);
    }

    private static void assertEmptyCredit(TextView author) {
        assertEquals("", author.getText().toString());
        assertEquals(View.INVISIBLE, author.getVisibility());
        assertNull(author.getCompoundDrawablesRelative()[0]);
        assertNull(author.getContentDescription());
        assertNull(author.getTooltipText());
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
        shadowOf(Looper.getMainLooper()).idle();
        card.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        card.layout(0, 0, card.getMeasuredWidth(), card.getMeasuredHeight());
        View strip = card.findViewById(R.id.gallery_state);
        for (int id : new int[]{R.id.gallery_favorite, R.id.gallery_online_favorite,
            R.id.gallery_local_files, R.id.gallery_reading_progress}) {
            View child = card.findViewById(id);
            assertTrue(child.getLeft() >= 0);
            assertTrue(child.getRight() <= strip.getWidth());
            assertTrue(child.getBottom() <= strip.getHeight());
        }
        TextView progress = card.findViewById(R.id.gallery_reading_progress);
        assertEquals(1, progress.getMaxLines());
        assertEquals(15f, progress.getTextSize(), 0f);
        assertTrue(progress.getHeight() <= Math.round(48 * activity.getResources().getDisplayMetrics().density));
        View cover = card.findViewById(R.id.gallery_cover);
        assertTrue(card.findViewById(R.id.title).getTop() >= cover.getBottom());
        assertTrue(progress.getBottom() <= card.findViewById(R.id.gallery_favorite).getTop());
        assertTrue(progress.getRight() <= strip.getWidth());
        assertTrue(card.findViewById(R.id.gallery_favorite).getRight()
            <= card.findViewById(R.id.gallery_local_files).getLeft());
        assertTrue(card.findViewById(R.id.gallery_online_favorite).getLeft()
            >= card.findViewById(R.id.gallery_favorite).getLeft());
        assertTrue(card.findViewById(R.id.gallery_online_favorite).getRight()
            <= card.findViewById(R.id.gallery_favorite).getRight());
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
    public void onlineFavoriteActionRequiresValidatedApiKeyAndKeepsLocalFavoriteIndependent() throws Exception {
        View card = LayoutInflater.from(activity).inflate(R.layout.entry_layout, null);
        activity.setContentView(card);
        SimpleGallery gallery = SimpleGallery.fromV2ListItem(activity, new JSONObject(
            "{\"id\":123,\"media_id\":\"1\",\"english_title\":\"Favorite state\",\"num_pages\":4}"));
        GalleryStateBadge badge = new GalleryStateBadge(card);
        badge.bind(gallery);
        awaitProgress(card, activity.getString(R.string.page_count_format, 4));
        View local = card.findViewById(R.id.gallery_favorite);
        View online = card.findViewById(R.id.gallery_online_favorite);
        assertEquals(View.INVISIBLE, online.getVisibility());

        AuthStore.saveApiKey(activity, "synthetic-test-key", true);
        badge.bind(gallery);
        awaitProgress(card, activity.getString(R.string.page_count_format, 4));
        assertEquals(View.VISIBLE, online.getVisibility());
        assertFalse(online.isClickable());
        assertFalse(online.isSelected());
        assertTrue(online.getTranslationY() > 0);
        assertEquals(.55f, online.getAlpha(), .001f);
        assertEquals(activity.getString(R.string.cover_online_favorite_unknown),
            androidx.core.view.ViewCompat.getStateDescription(local));
        OnlineFavoriteClient.rememberState(activity, 123, false);
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(activity.getString(R.string.cover_online_favorite_unselected),
            androidx.core.view.ViewCompat.getStateDescription(local));
        assertEquals(1f, online.getAlpha(), .001f);
        OnlineFavoriteClient.rememberState(activity, 123, true);
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(activity.getString(R.string.cover_online_favorite_selected),
            androidx.core.view.ViewCompat.getStateDescription(local));
        OnlineFavoriteClient.rememberState(activity, 123, false);
        shadowOf(Looper.getMainLooper()).idle();
        local.performClick();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!Queries.FavoriteTable.isFavorite(123) && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle();
            Thread.sleep(10);
        }
        assertTrue(Queries.FavoriteTable.isFavorite(123));
        assertEquals(View.VISIBLE, online.getVisibility());
        assertFalse(online.isSelected());
    }

    @Test
    public void favoriteOverlayDoesNotInterceptPrimaryTouch() throws Exception {
        View card = LayoutInflater.from(activity).inflate(R.layout.entry_layout, null);
        activity.setContentView(card);
        SimpleGallery gallery = SimpleGallery.fromV2ListItem(activity, new JSONObject(
            "{\"id\":124,\"media_id\":\"1\",\"english_title\":\"Touch zones\",\"num_pages\":2}"));
        activity.getSharedPreferences("Settings", Activity.MODE_PRIVATE).edit()
            .putString(activity.getString(R.string.preference_key_favorite_action_mode), "local").apply();
        AuthStore.saveApiKey(activity, "touch-zone-test-key", true);
        new GalleryStateBadge(card).bind(gallery);
        awaitProgress(card, activity.getString(R.string.page_count_format, 2));
        int width = Math.round(240 * activity.getResources().getDisplayMetrics().density);
        card.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        card.layout(0, 0, card.getMeasuredWidth(), card.getMeasuredHeight());
        View online = card.findViewById(R.id.gallery_online_favorite);
        View favorite = card.findViewById(R.id.gallery_favorite);
        assertEquals(View.VISIBLE, online.getVisibility());
        assertFalse(online.isClickable());

        long downTime = android.os.SystemClock.uptimeMillis();
        float x = favorite.getWidth() / 2f;
        float y = favorite.getHeight() / 2f;
        android.view.MotionEvent down = android.view.MotionEvent.obtain(downTime, downTime,
            android.view.MotionEvent.ACTION_DOWN, x, y, 0);
        android.view.MotionEvent up = android.view.MotionEvent.obtain(downTime, downTime + 20,
            android.view.MotionEvent.ACTION_UP, x, y, 0);
        try {
            favorite.dispatchTouchEvent(down);
            favorite.dispatchTouchEvent(up);
        } finally {
            down.recycle();
            up.recycle();
        }

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!Queries.FavoriteTable.isFavorite(124) && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle();
            Thread.sleep(10);
        }
        assertTrue(Queries.FavoriteTable.isFavorite(124));
    }

    @Test
    public void starPrimaryDisplaysBothStatesAndLongPressChangesLocalFavorite() throws Exception {
        View card = LayoutInflater.from(activity).inflate(R.layout.entry_layout, null);
        activity.setContentView(card);
        SimpleGallery gallery = SimpleGallery.fromV2ListItem(activity, new JSONObject(
            "{\"id\":126,\"media_id\":\"1\",\"english_title\":\"Star primary\",\"num_pages\":2}"));
        activity.getSharedPreferences("Settings", Activity.MODE_PRIVATE).edit()
            .putString(activity.getString(R.string.preference_key_favorite_action_mode), "online").apply();
        AuthStore.saveApiKey(activity, "star-primary-test-key", true);
        OnlineFavoriteClient.rememberState(activity, 126, true);
        GalleryStateBadge badge = new GalleryStateBadge(card);
        badge.bind(gallery);
        awaitProgress(card, activity.getString(R.string.page_count_format, 2));
        View primary = card.findViewById(R.id.gallery_favorite);
        View secondary = card.findViewById(R.id.gallery_online_favorite);
        assertTrue(primary.isSelected());
        assertFalse(secondary.isSelected());
        assertTrue(secondary.getTranslationY() > 0);
        assertEquals(activity.getString(R.string.cover_online_favorite_remove), primary.getContentDescription());
        assertEquals(activity.getString(R.string.cover_local_favorite_unselected),
            androidx.core.view.ViewCompat.getStateDescription(primary));
        assertTrue(primary.performLongClick());
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!Queries.FavoriteTable.isFavorite(126) && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle();
            Thread.sleep(10);
        }
        assertTrue(Queries.FavoriteTable.isFavorite(126));
        assertEquals(Boolean.TRUE, OnlineFavoriteClient.cachedState(activity, 126));

        AuthStore.clear(activity);
        badge.bind(gallery);
        awaitProgress(card, activity.getString(R.string.page_count_format, 2));
        assertEquals(View.INVISIBLE, secondary.getVisibility());
        assertNull(androidx.core.view.ViewCompat.getStateDescription(primary));
        assertEquals(activity.getString(R.string.cover_remove_favorite), primary.getContentDescription());
    }

    @Test
    public void secondaryFavoriteScalesWithCardWidth() {
        View card = LayoutInflater.from(activity).inflate(R.layout.entry_layout, null);
        new GalleryStateBadge(card);
        float density = activity.getResources().getDisplayMetrics().density;
        View secondary = card.findViewById(R.id.gallery_online_favorite);
        for (int width : new int[]{116, 180}) {
            int pixels = Math.round(width * density);
            card.measure(View.MeasureSpec.makeMeasureSpec(pixels, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            card.layout(0, 0, card.getMeasuredWidth(), card.getMeasuredHeight());
            assertEquals(width == 116 ? 1f : 14f / 12f, secondary.getScaleX(), .001f);
            assertEquals(secondary.getScaleX(), secondary.getScaleY(), .001f);
            assertEquals((width == 116 ? 6 : 9) * density, secondary.getTranslationX(), .001f);
            assertEquals(secondary.getTranslationX(), secondary.getTranslationY(), .001f);
        }
    }

    @Test
    public void heartBadgeUsesSmallerScaleThanStarBadgeAcrossCardWidths() throws Exception {
        AuthStore.saveApiKey(activity, "badge-scale-test-key", true);
        View card = LayoutInflater.from(activity).inflate(R.layout.entry_layout, null);
        activity.setContentView(card);
        GalleryStateBadge badge = new GalleryStateBadge(card);
        SimpleGallery gallery = SimpleGallery.fromV2ListItem(activity, new JSONObject(
            "{\"id\":132,\"media_id\":\"1\",\"english_title\":\"Badge scale\",\"num_pages\":2}"));
        View secondary = card.findViewById(R.id.gallery_online_favorite);
        float density = activity.getResources().getDisplayMetrics().density;
        for (int width : new int[]{116, 145, 180}) {
            int pixels = Math.round(width * density);
            card.measure(View.MeasureSpec.makeMeasureSpec(pixels, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            card.layout(0, 0, card.getMeasuredWidth(), card.getMeasuredHeight());
            for (String mode : new String[]{"online", "local"}) {
                activity.getSharedPreferences("Settings", Activity.MODE_PRIVATE).edit()
                    .putString(activity.getString(R.string.preference_key_favorite_action_mode), mode).apply();
                badge.bind(gallery);
                awaitProgress(card, activity.getString(R.string.page_count_format, 2));
                card.measure(View.MeasureSpec.makeMeasureSpec(pixels, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
                card.layout(0, 0, card.getMeasuredWidth(), card.getMeasuredHeight());
                float expected = "online".equals(mode) ? (width <= 160 ? 8f : 11f) / 12f
                    : (width <= 140 ? 1f : 14f / 12f);
                assertEquals(expected, secondary.getScaleX(), .001f);
                assertEquals(expected, secondary.getScaleY(), .001f);
                int padding = Math.round(16 * density);
                assertEquals(padding, card.findViewById(R.id.gallery_favorite).getPaddingLeft());
            }
        }
    }

    @Test
    public void secondaryFavoriteKeepsItsSizeBeforeStateLoads() throws Exception {
        AuthStore.saveApiKey(activity, "layout-test-key", true);
        View card = LayoutInflater.from(activity).inflate(R.layout.entry_layout, null);
        activity.setContentView(card);
        GalleryStateBadge badge = new GalleryStateBadge(card);
        int width = Math.round(116 * activity.getResources().getDisplayMetrics().density);
        card.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        card.layout(0, 0, card.getMeasuredWidth(), card.getMeasuredHeight());
        View secondary = card.findViewById(R.id.gallery_online_favorite);
        assertEquals(View.INVISIBLE, secondary.getVisibility());
        assertTrue(secondary.getWidth() > 0);
        badge.bind(SimpleGallery.fromV2ListItem(activity, new JSONObject(
            "{\"id\":131,\"media_id\":\"1\",\"english_title\":\"Layout test\",\"num_pages\":2}")));
        awaitProgress(card, activity.getString(R.string.page_count_format, 2));
        assertEquals(View.VISIBLE, secondary.getVisibility());
        assertTrue(secondary.getWidth() > 0);
    }

    @Test
    public void downloadedGalleryWithIdShowsBothFavoriteStatesInEitherMode() throws Exception {
        AuthStore.saveApiKey(activity, "local-card-test-key", true);
        LocalGallery local = gallery(129, "{\"num_pages\":1}");
        OnlineFavoriteClient.rememberState(activity, 129, true);
        View card = LayoutInflater.from(activity).inflate(R.layout.entry_layout, null);
        activity.setContentView(card);
        GalleryStateBadge badge = new GalleryStateBadge(card);
        for (String mode : new String[]{"local", "online"}) {
            activity.getSharedPreferences("Settings", Activity.MODE_PRIVATE).edit()
                .putString(activity.getString(R.string.preference_key_favorite_action_mode), mode).apply();
            badge.bind(local);
            awaitProgress(card, activity.getString(R.string.page_count_format, 1));
            View primary = card.findViewById(R.id.gallery_favorite);
            View secondary = card.findViewById(R.id.gallery_online_favorite);
            assertEquals(View.VISIBLE, secondary.getVisibility());
            assertTrue(secondary.getTranslationY() > 0);
            assertEquals("online".equals(mode), primary.isSelected());
            assertEquals("local".equals(mode), secondary.isSelected());
        }
    }

    @Test
    public void downloadedGalleryCanChangeOnlineFavoriteWithoutChangingLocalFavorite() throws Exception {
        AuthStore.saveApiKey(activity, "local-online-action-key", true);
        activity.getSharedPreferences("Settings", Activity.MODE_PRIVATE).edit()
            .putString(activity.getString(R.string.preference_key_favorite_action_mode), "local").apply();
        LocalGallery local = gallery(130, "{\"num_pages\":1}");
        OnlineFavoriteClient.rememberState(activity, 130, false);
        okhttp3.OkHttpClient previous = Global.client;
        try {
            Global.client = new okhttp3.OkHttpClient.Builder()
                .addInterceptor(new com.maxwai.nclientv3.settings.ApiAuthInterceptor(activity))
                .addInterceptor(chain -> new okhttp3.Response.Builder().request(chain.request())
                    .protocol(okhttp3.Protocol.HTTP_1_1).code(200).message("OK")
                    .body(okhttp3.ResponseBody.create("{\"favorited\":true}", null)).build()).build();
            View card = LayoutInflater.from(activity).inflate(R.layout.entry_layout, null);
            activity.setContentView(card);
            new GalleryStateBadge(card).bind(local);
            awaitProgress(card, activity.getString(R.string.page_count_format, 1));
            assertTrue(card.findViewById(R.id.gallery_favorite).performLongClick());
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!Boolean.TRUE.equals(OnlineFavoriteClient.cachedState(activity, 130)) && System.nanoTime() < deadline) {
                shadowOf(Looper.getMainLooper()).idle();
                Thread.sleep(10);
            }
            assertEquals(Boolean.TRUE, OnlineFavoriteClient.cachedState(activity, 130));
            assertFalse(Queries.FavoriteTable.isFavorite(130));
        } finally {
            Global.client = previous;
        }
    }

    @Test
    public void onlineChangesRefreshVisibleCopiesAndReattachedCards() throws Exception {
        activity.getSharedPreferences("Settings", Activity.MODE_PRIVATE).edit()
            .putString(activity.getString(R.string.preference_key_favorite_action_mode), "local").apply();
        AuthStore.saveApiKey(activity, "observer-test-key", true);
        SimpleGallery gallery = SimpleGallery.fromV2ListItem(activity, new JSONObject(
            "{\"id\":127,\"media_id\":\"1\",\"english_title\":\"Duplicate card\",\"num_pages\":2}"));
        android.widget.LinearLayout container = new android.widget.LinearLayout(activity);
        View first = LayoutInflater.from(activity).inflate(R.layout.entry_layout, container, false);
        View second = LayoutInflater.from(activity).inflate(R.layout.entry_layout, container, false);
        GalleryStateBadge firstBadge = new GalleryStateBadge(first);
        GalleryStateBadge secondBadge = new GalleryStateBadge(second);
        container.addView(first);
        container.addView(second);
        activity.setContentView(container);
        firstBadge.bind(gallery);
        secondBadge.bind(gallery);
        awaitProgress(first, activity.getString(R.string.page_count_format, 2));
        awaitProgress(second, activity.getString(R.string.page_count_format, 2));
        View firstStar = first.findViewById(R.id.gallery_online_favorite);
        View secondStar = second.findViewById(R.id.gallery_online_favorite);
        OnlineFavoriteClient.rememberState(activity, 127, true);
        shadowOf(Looper.getMainLooper()).idle();
        assertTrue(firstStar.isSelected());
        assertTrue(secondStar.isSelected());

        container.removeView(second);
        OnlineFavoriteClient.rememberState(activity, 127, false);
        shadowOf(Looper.getMainLooper()).idle();
        assertFalse(firstStar.isSelected());
        assertTrue(secondStar.isSelected());
        container.addView(second);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (secondStar.isSelected() && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle();
            Thread.sleep(10);
        }
        assertFalse(secondStar.isSelected());

        OnlineFavoriteClient.rememberState(activity, 127, true);
        shadowOf(Looper.getMainLooper()).idle();
        assertTrue(firstStar.isSelected());
        assertTrue(secondStar.isSelected());
        AuthStore.saveApiKey(activity, "another-observer-key", true);
        shadowOf(Looper.getMainLooper()).idle();
        assertFalse(firstStar.isSelected());
        assertFalse(secondStar.isSelected());
        OnlineFavoriteClient.rememberState(activity, 127, true);
        shadowOf(Looper.getMainLooper()).idle();
        assertTrue(firstStar.isSelected());
        AuthStore.setApiKeyValidation(activity, false);
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(View.INVISIBLE, firstStar.getVisibility());
        assertEquals(View.INVISIBLE, secondStar.getVisibility());
        AuthStore.saveApiKey(activity, "third-observer-key", true);
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(View.VISIBLE, firstStar.getVisibility());
        assertFalse(firstStar.isSelected());
        AuthStore.clear(activity);
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(View.INVISIBLE, firstStar.getVisibility());
    }

    @Test
    public void evictedOnlineStateReturnsVisibleCardToUnknown() throws Exception {
        activity.getSharedPreferences("Settings", Activity.MODE_PRIVATE).edit()
            .putString(activity.getString(R.string.preference_key_favorite_action_mode), "local").apply();
        AuthStore.saveApiKey(activity, "eviction-test-key", true);
        SimpleGallery gallery = SimpleGallery.fromV2ListItem(activity, new JSONObject(
            "{\"id\":128,\"media_id\":\"1\",\"english_title\":\"Evicted card\",\"num_pages\":2}"));
        View card = LayoutInflater.from(activity).inflate(R.layout.entry_layout, null);
        activity.setContentView(card);
        new GalleryStateBadge(card).bind(gallery);
        awaitProgress(card, activity.getString(R.string.page_count_format, 2));
        View star = card.findViewById(R.id.gallery_online_favorite);
        OnlineFavoriteClient.rememberState(activity, 128, true);
        shadowOf(Looper.getMainLooper()).idle();
        assertTrue(star.isSelected());
        for (int id = 1000; id < 1512; id++) OnlineFavoriteClient.rememberState(activity, id, false);
        shadowOf(Looper.getMainLooper()).idle();
        assertNull(OnlineFavoriteClient.cachedState(activity, 128));
        assertFalse(star.isSelected());
    }

    @Test
    public void absentOnlineFavoriteFieldRemainsUnknownAcrossParcelTransfer() throws Exception {
        Gallery gallery = new Gallery(activity,
            "{\"id\":125,\"num_pages\":2,\"title\":{\"pretty\":\"Unknown favorite\"},"
                + "\"cover\":{\"path\":\"galleries/1/cover.png\"},"
                + "\"thumbnail\":{\"path\":\"galleries/1/thumb.png\"},"
                + "\"pages\":[{\"path\":\"galleries/1/1.png\",\"width\":1,\"height\":1},"
                + "{\"path\":\"galleries/1/2.png\",\"width\":1,\"height\":1}]}", null, (Boolean) null);
        android.os.Parcel parcel = android.os.Parcel.obtain();
        try {
            gallery.writeToParcel(parcel, 0);
            parcel.setDataPosition(0);
            Gallery restored = Gallery.CREATOR.createFromParcel(parcel);
            assertNull(restored.isOnlineFavorite());
        } finally {
            parcel.recycle();
        }
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
    public void relatedGalleryPrefersFullCreditsOverCachedTagIds() throws Exception {
        SimpleGallery related = SimpleGallery.fromV2ListItem(activity, new JSONObject(
            "{\"id\":122,\"media_id\":\"1\",\"english_title\":\"Related test\","
                + "\"tag_ids\":[999],\"tags\":["
                + "{\"id\":901,\"name\":\"Circle from response\",\"type\":\"group\"},"
                + "{\"id\":902,\"name\":\"Artist from response\",\"type\":\"artist\"}]}"));
        assertEquals(1, related.getTags().getCount(TagType.GROUP));
        assertEquals("Circle from response", related.getTags().getTag(TagType.GROUP, 0).getName());
        assertEquals(1, related.getTags().getCount(TagType.ARTIST));
        assertEquals("Artist from response", related.getTags().getTag(TagType.ARTIST, 0).getName());
        Parcel parcel = Parcel.obtain();
        try {
            related.writeToParcel(parcel, 0);
            parcel.setDataPosition(0);
            SimpleGallery restored = SimpleGallery.CREATOR.createFromParcel(parcel);
            assertEquals(1, restored.getTags().getCount(TagType.GROUP));
            assertEquals(1, restored.getTagIds().size());
            assertEquals(Integer.valueOf(999), restored.getTagIds().get(0));
        } finally {
            parcel.recycle();
        }
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
