// NReader modifications by TheSkyC (2026); see NOTICE.
package com.maxwai.nclientv3.components.widgets;

import android.app.Application;
import android.graphics.RectF;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.progressindicator.CircularProgressIndicator;
import com.maxwai.nclientv3.R;
import com.maxwai.nclientv3.settings.MainGridSettings;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class GalleryListGridTest {
    private ActivityController<AppCompatActivity> activityController;
    private AppCompatActivity activity;
    private RecyclerView recycler;
    private GalleryListGrid grid;
    private Menu menu;

    @Before
    public void setUp() {
        activityController = Robolectric.buildActivity(AppCompatActivity.class);
        activity = activityController.get();
        activity.setTheme(R.style.AppTheme);
        activityController.setup();
        activity.getSharedPreferences("Settings", 0).edit().clear().commit();
        recycler = new RecyclerView(activity);
        int padding = pixels(6);
        recycler.setPadding(padding, padding, padding, padding);
        activity.setContentView(recycler);
        recycler.setAdapter(new Cards());
        grid = new GalleryListGrid(recycler);
        menu = new androidx.appcompat.widget.PopupMenu(activity, recycler).getMenu();
        activity.getMenuInflater().inflate(R.menu.main, menu);
        assertFalse(menu.findItem(R.id.list_columns).isVisible());
        grid.bindMenu(menu.findItem(R.id.list_columns));
        layout(412);
    }

    @After
    public void tearDown() {
        grid.close();
        activityController.pause().stop().destroy();
    }

    private int pixels(int dp) {
        return Math.round(dp * activity.getResources().getDisplayMetrics().density);
    }

    private void layout(int widthDp) {
        int width = pixels(widthDp);
        int height = pixels(700);
        for (int pass = 0; pass < 4; pass++) {
            recycler.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
            recycler.layout(0, 0, width, height);
            grid.refresh();
        }
    }

    @Test
    public void switchingPreservesAdapterAndVisibleGalleryOffset() {
        CustomGridLayoutManager manager = (CustomGridLayoutManager) recycler.getLayoutManager();
        RecyclerView.Adapter<?> adapter = recycler.getAdapter();
        manager.scrollToPositionWithOffset(44, -pixels(20));
        layout(412);
        int position = manager.findFirstVisibleItemPosition();
        assertTrue(position > 0);
        View anchor = manager.findViewByPosition(position);
        assertNotNull(anchor);
        int offset = manager.getDecoratedTop(anchor) - recycler.getPaddingTop();
        grid.cycle();
        layout(412);
        assertEquals(3, manager.getSpanCount());
        assertSame(adapter, recycler.getAdapter());
        assertEquals(120, adapter.getItemCount());
        View restored = manager.findViewByPosition(position);
        assertNotNull(restored);
        assertEquals(offset, manager.getDecoratedTop(restored) - recycler.getPaddingTop());
        View favorite = restored.findViewById(R.id.gallery_favorite);
        View download = restored.findViewById(R.id.gallery_local_files);
        assertTrue(favorite.getRight() <= download.getLeft());
        assertEquals(pixels(40), favorite.getWidth());
        assertEquals(pixels(40), download.getWidth());
        assertEquals(pixels(12), favorite.getPaddingLeft());
        assertEquals(pixels(12), download.getPaddingLeft());
        for (View control : new View[]{favorite, download}) {
            ImageView button = (ImageView) control;
            RectF artwork = new RectF(0, 0, button.getDrawable().getIntrinsicWidth(),
                button.getDrawable().getIntrinsicHeight());
            button.getImageMatrix().mapRect(artwork);
            assertEquals(pixels(16), artwork.width(), 1);
            assertEquals(pixels(16), artwork.height(), 1);
        }
        CircularProgressIndicator progress = restored.findViewById(R.id.gallery_download_progress);
        assertEquals(pixels(34), progress.getIndicatorSize());
        ImageView cover = restored.findViewById(R.id.image);
        assertEquals(cover.getWidth() * 1.5f, cover.getHeight(), 1);
        grid.cycle();
        layout(412);
        restored = manager.findViewByPosition(position);
        assertNotNull(restored);
        assertEquals(pixels(48), restored.findViewById(R.id.gallery_favorite).getWidth());
        assertEquals(pixels(48), restored.findViewById(R.id.gallery_local_files).getWidth());
        assertEquals(pixels(16), restored.findViewById(R.id.gallery_favorite).getPaddingLeft());
        assertEquals(pixels(16), restored.findViewById(R.id.gallery_local_files).getPaddingLeft());
        assertEquals(ImageView.ScaleType.CENTER,
            ((ImageView) restored.findViewById(R.id.gallery_favorite)).getScaleType());
        assertEquals(ImageView.ScaleType.CENTER,
            ((ImageView) restored.findViewById(R.id.gallery_local_files)).getScaleType());
        progress = restored.findViewById(R.id.gallery_download_progress);
        assertEquals(pixels(36), progress.getIndicatorSize());
    }

    @Test
    public void denseGridPreservesLargerOnlinePrimaryIconAcrossRefreshes() {
        grid.cycle();
        layout(412);
        ImageView favorite = recycler.getChildAt(0).findViewById(R.id.gallery_favorite);
        favorite.setTag(R.id.gallery_online_favorite, true);
        grid.refresh();
        assertEquals(pixels(10), favorite.getPaddingLeft());
        assertEquals(pixels(40), favorite.getWidth());
        layout(412);
        assertEquals(pixels(10), favorite.getPaddingLeft());
        RectF artwork = new RectF(0, 0, favorite.getDrawable().getIntrinsicWidth(),
            favorite.getDrawable().getIntrinsicHeight());
        favorite.getImageMatrix().mapRect(artwork);
        assertEquals(pixels(20), artwork.width(), 1);
        favorite.setTag(R.id.gallery_online_favorite, false);
        grid.refresh();
        assertEquals(pixels(12), favorite.getPaddingLeft());
    }

    @Test
    public void shrinkingADeeplyClippedRowKeepsTheGalleryVisible() {
        CustomGridLayoutManager manager = (CustomGridLayoutManager) recycler.getLayoutManager();
        manager.scrollToPositionWithOffset(44, -pixels(260));
        layout(412);
        int position = manager.findFirstVisibleItemPosition();
        assertTrue(position > 0);
        grid.cycle();
        layout(412);
        View restored = manager.findViewByPosition(position);
        assertNotNull(restored);
        assertTrue(manager.getDecoratedBottom(restored) > recycler.getPaddingTop());
    }

    @Test
    public void downloadedGridRestoresItsChoiceAndKeepsTheOnlinePreferenceSeparate() {
        RecyclerView.Adapter<?> adapter = recycler.getAdapter();
        grid.close();
        grid = new GalleryListGrid(recycler, MainGridSettings.forDownloads(activity));
        menu.clear();
        activity.getMenuInflater().inflate(R.menu.download, menu);
        grid.bindMenu(menu.findItem(R.id.list_columns));
        layout(412);
        grid.cycle();
        layout(412);
        assertSame(adapter, recycler.getAdapter());
        assertEquals(3, ((CustomGridLayoutManager) recycler.getLayoutManager()).getSpanCount());
        assertEquals(2, new MainGridSettings(activity).columns(400, 1));
        assertEquals(activity.getString(R.string.list_columns_action, 3, 2),
            menu.findItem(R.id.list_columns).getContentDescription());
        layout(220);
        assertEquals(1, ((CustomGridLayoutManager) recycler.getLayoutManager()).getSpanCount());
        layout(412);
        assertEquals(3, ((CustomGridLayoutManager) recycler.getLayoutManager()).getSpanCount());
        View card = recycler.getChildAt(0);
        assertEquals(pixels(40), card.findViewById(R.id.gallery_favorite).getWidth());
        assertEquals(ImageView.ScaleType.FIT_CENTER,
            ((ImageView) card.findViewById(R.id.gallery_local_files)).getScaleType());
        assertEquals(pixels(40), card.findViewById(R.id.gallery_local_files).getWidth());
    }

    @Test
    public void resizingRestoresWideChoiceAndKeepsMenuInSync() {
        grid.cycle();
        layout(412);
        assertEquals(3, ((CustomGridLayoutManager) recycler.getLayoutManager()).getSpanCount());
        layout(732);
        grid.cycle();
        layout(732);
        grid.cycle();
        layout(732);
        assertEquals(4, ((CustomGridLayoutManager) recycler.getLayoutManager()).getSpanCount());
        assertEquals(activity.getString(R.string.list_columns_action, 4, 2),
            menu.findItem(R.id.list_columns).getContentDescription());
        layout(412);
        assertEquals(3, ((CustomGridLayoutManager) recycler.getLayoutManager()).getSpanCount());
        layout(332);
        assertEquals(2, ((CustomGridLayoutManager) recycler.getLayoutManager()).getSpanCount());
        assertFalse(menu.findItem(R.id.list_columns).isEnabled());
        grid.cycle();
        layout(732);
        assertEquals(4, ((CustomGridLayoutManager) recycler.getLayoutManager()).getSpanCount());
        assertTrue(menu.findItem(R.id.list_columns).isEnabled());
        layout(412);
        assertEquals(3, ((CustomGridLayoutManager) recycler.getLayoutManager()).getSpanCount());
    }

    private static final class Cards extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View card = LayoutInflater.from(parent.getContext()).inflate(R.layout.entry_layout, parent, false);
            return new RecyclerView.ViewHolder(card) {};
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            ((TextView) holder.itemView.findViewById(R.id.title)).setText("Gallery " + position);
        }

        @Override
        public int getItemCount() {
            return 120;
        }
    }
}
