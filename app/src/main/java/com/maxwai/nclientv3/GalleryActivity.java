// Modified for NReader by TheSkyC (2026); see NOTICE.
package com.maxwai.nclientv3;

import android.Manifest;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Parcelable;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.ViewTreeObserver;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckedTextView;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.RecyclerViewAccessibilityDelegate;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.android.material.tabs.TabLayout;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.maxwai.nclientv3.adapters.GalleryAdapter;
import com.maxwai.nclientv3.adapters.CommentAdapter;
import com.maxwai.nclientv3.api.comments.CommentsFetcher;
import com.maxwai.nclientv3.api.comments.Comment;
import com.maxwai.nclientv3.api.InspectorV3;
import com.maxwai.nclientv3.api.components.Gallery;
import com.maxwai.nclientv3.api.components.GenericGallery;
import com.maxwai.nclientv3.async.database.Queries;
import com.maxwai.nclientv3.async.database.ReadingProgress;
import com.maxwai.nclientv3.components.classes.ReadingPosition;
import com.maxwai.nclientv3.components.activities.BaseActivity;
import com.maxwai.nclientv3.components.status.Status;
import com.maxwai.nclientv3.components.status.StatusManager;
import com.maxwai.nclientv3.components.views.RangeSelector;
import com.maxwai.nclientv3.components.widgets.CustomGridLayoutManager;
import com.maxwai.nclientv3.components.widgets.GalleryTabSwipeLayout;
import com.maxwai.nclientv3.settings.AuthStore;
import com.maxwai.nclientv3.settings.DefaultDialogs;
import com.maxwai.nclientv3.settings.Favorites;
import com.maxwai.nclientv3.settings.Global;
import com.maxwai.nclientv3.utility.LogUtility;
import com.maxwai.nclientv3.utility.Utility;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import yuku.ambilwarna.AmbilWarnaDialog;

public class GalleryActivity extends BaseActivity {
    @NonNull
    private GenericGallery gallery = Gallery.emptyGallery();
    private boolean isLocal;
    private GalleryAdapter adapter;
    private int zoom;
    private boolean isLocalFavorite;
    private Toolbar toolbar;
    private RecyclerView detailsList;
    private GalleryAdapter detailsAdapter;
    private TabLayout tabs;
    private RecyclerView commentsList;
    private SwipeRefreshLayout commentsRefresher;
    private boolean commentsLoaded;
    private boolean commentsLoading;
    private int commentsPage;
    private int commentsPages;
    private int commentsRequestedPage = 1;
    private CommentAdapter commentsAdapter;
    private MenuItem onlineFavoriteItem;
    private boolean onlineFavoritePending;
    private String statusString;

    private int newStatusColor;
    private ReadingPosition automaticPosition;
    private boolean positionReady;
    private boolean readerOpened;
    private boolean restoringPosition;
    private ReadingPosition pendingPosition;
    private boolean scrollDirty;
    private int displayedTab;
    private ReadingPosition activeScrollTarget;
    private ViewTreeObserver.OnGlobalLayoutListener restoreListener;

    private static class RetainedComments {
        final List<Comment> comments;
        final int page;
        final int pages;
        final Parcelable layoutState;

        RetainedComments(List<Comment> comments, int page, int pages, Parcelable layoutState) {
            this.comments = comments;
            this.page = page;
            this.pages = pages;
            this.layoutState = layoutState;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        //Global.initActivity(this);
        setContentView(R.layout.activity_gallery);
        if (Global.isLockScreen())
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        ActionBar actionBar = Objects.requireNonNull(getSupportActionBar());
        actionBar.setDisplayHomeAsUpEnabled(true);
        actionBar.setDisplayShowTitleEnabled(true);
        recycler = findViewById(R.id.recycler);
        refresher = findViewById(R.id.refresher);
        detailsList = findViewById(R.id.gallery_details);
        commentsList = findViewById(R.id.comments_list);
        commentsRefresher = findViewById(R.id.comments_refresher);
        tabs = findViewById(R.id.gallery_tabs);
        masterLayout = findViewById(R.id.master_layout);
        GenericGallery gal;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gal = getIntent().getParcelableExtra(getPackageName() + ".GALLERY", GenericGallery.class);
        } else {
            gal = getIntent().getParcelableExtra(getPackageName() + ".GALLERY");
        }
        if (gal == null) {
            if (!tryLoadFromURL()) finish();
            return;
        }
        if (gal != null) this.gallery = gal;
        if (gallery.getType() != GenericGallery.Type.LOCAL) {
            Queries.HistoryTable.addGallery(((Gallery) gallery).toSimpleGallery());
        }
        LogUtility.d("" + gallery);
        if (Global.useRtl()) {
            recycler.setRotationY(180);
            detailsList.setRotationY(180);
        }
        isLocal = gallery.isLocal() || getIntent().getBooleanExtra(getPackageName() + ".ISLOCAL", false);
        zoom = getIntent().getIntExtra(getPackageName() + ".ZOOM", 0);
        refresher.setEnabled(false);
        recycler.setLayoutManager(new CustomGridLayoutManager(this, Global.getColumnCount()));

        loadGallery(gallery, zoom);//if already has gallery
        setupTabs(savedInstanceState);
        recycler.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                if (dy != 0 && displayedTab == 1 && positionReady && !restoringPosition) scrollDirty = true;
            }

            @Override
            public void onScrollStateChanged(@NonNull RecyclerView recyclerView, int newState) {
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING) stopRestoringPosition();
                if (newState == RecyclerView.SCROLL_STATE_IDLE) saveScrollPosition();
            }
        });
        recycler.setOnGenericMotionListener((view, event) -> {
            if (event.getAction() == MotionEvent.ACTION_SCROLL) stopRestoringPosition();
            return false;
        });
        recycler.setAccessibilityDelegateCompat(new RecyclerViewAccessibilityDelegate(recycler) {
            @Override
            public boolean performAccessibilityAction(@NonNull View host, int action, @Nullable Bundle args) {
                stopRestoringPosition();
                return super.performAccessibilityAction(host, action, args);
            }
        });
        if (savedInstanceState != null && savedInstanceState.getBoolean("continuousReader", false)) {
            pendingPosition = new ReadingPosition(savedInstanceState.getInt("readerPage", 1),
                savedInstanceState.getFloat("readerOffset", 0f)).clamp(gallery.getPageCount());
            readerOpened = true;
        }
        loadAutomaticPosition();
    }

    private boolean tryLoadFromURL() {
        Uri data = getIntent().getData();
        if (data != null && data.getPathSegments().size() >= 2) {//if using an URL
            List<String> params = data.getPathSegments();
            LogUtility.d(params.size() + ": " + params);
            int id;
            try {//if not an id return
                id = Integer.parseInt(params.get(1));
            } catch (NumberFormatException ignore) {
                return false;
            }
            if (params.size() > 2) {//check if it has a specific page
                try {
                    zoom = Integer.parseInt(params.get(2));
                } catch (NumberFormatException e) {
                    LogUtility.e(e.getLocalizedMessage(), e);
                    zoom = 0;
                }
            }
            InspectorV3.galleryInspector(this, id, new InspectorV3.DefaultInspectorResponse() {
                @Override
                public void onSuccess(List<GenericGallery> galleries) {
                    if (!galleries.isEmpty()) {
                        Intent intent = new Intent(GalleryActivity.this, GalleryActivity.class);
                        intent.putExtra(getPackageName() + ".GALLERY", galleries.get(0));
                        intent.putExtra(getPackageName() + ".ZOOM", zoom);
                        startActivity(intent);
                    }
                    finish();
                }
            }).start();
            return true;
        }
        return false;
    }

    private void lookup() {
        CustomGridLayoutManager manager = (CustomGridLayoutManager) recycler.getLayoutManager();
        GalleryAdapter adapter = (GalleryAdapter) recycler.getAdapter();
        Objects.requireNonNull(manager).setSpanSizeLookup(new CustomGridLayoutManager.SpanSizeLookup() {
            @Override
            public int getSpanSize(int position) {
                return adapter == null ? 0 : (adapter.positionToType(position) == GalleryAdapter.Type.PAGE ? 1 : manager.getSpanCount());
            }
        });
    }

    private void loadGallery(GenericGallery gall, int zoom) {
        this.gallery = gall;
        if (getSupportActionBar() != null) {
            applyTitle();
        }
        adapter = new GalleryAdapter(this, gallery, Global.getColumnCount());
        recycler.setAdapter(adapter);
        detailsAdapter = new GalleryAdapter(this, gallery, 1, true);
        detailsList.setLayoutManager(new LinearLayoutManager(this));
        detailsList.setAdapter(detailsAdapter);
        lookup();
        if (zoom > 0 && (gallery.isLocal() || Global.getDownloadPolicy() != Global.DataUsageType.NONE)) {
            Intent intent = new Intent(this, ZoomActivity.class);
            intent.putExtra(getPackageName() + ".GALLERY", this.gallery);
            intent.putExtra(getPackageName() + ".DIRECTORY", adapter.getDirectory());
            intent.putExtra(getPackageName() + ".PAGE", zoom);
            startActivity(intent);
        }
    }

    private void loadAutomaticPosition() {
        ReadingProgress.load(gallery.getId(), position -> runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;
            automaticPosition = position == null ? null : position.clamp(gallery.getPageCount());
            positionReady = true;
            if (detailsAdapter != null) detailsAdapter.notifyItemChanged(0);
            supportInvalidateOptionsMenu();
            if (pendingPosition != null) {
                ReadingPosition pending = pendingPosition;
                pendingPosition = null;
                resumeReading(pending);
            }
        }));
    }

    private void resumeReading(ReadingPosition position) {
        if (position == null || gallery.getPageCount() <= 0) return;
        ReadingPosition target = position.clamp(gallery.getPageCount());
        if (Global.getColumnCount() == 1) {
            selectTab(1);
            stopRestoringPosition();
            readerOpened = true;
            restoringPosition = true;
            automaticPosition = target;
            activeScrollTarget = target;
            CustomGridLayoutManager manager = (CustomGridLayoutManager) recycler.getLayoutManager();
            if (manager == null) {
                stopRestoringPosition();
                return;
            }
            final int[] lastHeight = {-1};
            restoreListener = () -> {
                View pageView = manager.findViewByPosition(target.page);
                if (pageView != null && pageView.getHeight() > 0 && lastHeight[0] != pageView.getHeight()) {
                    lastHeight[0] = pageView.getHeight();
                    manager.scrollToPositionWithOffset(target.page, -(int) (lastHeight[0] * target.offset));
                }
            };
            recycler.getViewTreeObserver().addOnGlobalLayoutListener(restoreListener);
            manager.scrollToPositionWithOffset(target.page, 0);
            ReadingProgress.save(gallery.getId(), target);
        } else openPagedReader(target.page);
    }

    private void stopRestoringPosition() {
        if (restoreListener != null && recycler != null)
            recycler.getViewTreeObserver().removeOnGlobalLayoutListener(restoreListener);
        restoreListener = null;
        activeScrollTarget = null;
        restoringPosition = false;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) stopRestoringPosition();
        return super.dispatchKeyEvent(event);
    }

    @Override
    protected void onDestroy() {
        stopRestoringPosition();
        super.onDestroy();
    }

    @Nullable
    private ReadingPosition scrollPosition() {
        if (recycler == null || displayedTab != 1 || Global.getColumnCount() != 1 || restoringPosition) return null;
        CustomGridLayoutManager manager = (CustomGridLayoutManager) recycler.getLayoutManager();
        if (manager == null) return null;
        int page = manager.findFirstVisibleItemPosition();
        if (page < 1 || page > gallery.getPageCount()) return null;
        View pageView = manager.findViewByPosition(page);
        if (pageView == null || pageView.getHeight() <= 0) return null;
        return new ReadingPosition(page, Math.max(0, recycler.getPaddingTop() - pageView.getTop()) / (float) pageView.getHeight());
    }

    private void saveScrollPosition() {
        if (!positionReady || !scrollDirty) return;
        ReadingPosition position = scrollPosition();
        if (position == null) return;
        readerOpened = true;
        automaticPosition = position;
        ReadingProgress.save(gallery.getId(), position);
        scrollDirty = false;
        if (detailsAdapter != null) detailsAdapter.notifyItemChanged(0);
    }

    @Override
    protected void onPause() {
        saveScrollPosition();
        super.onPause();
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle state) {
        saveScrollPosition();
        ReadingPosition currentPosition = displayedTab == 1
            ? (activeScrollTarget != null ? activeScrollTarget : scrollPosition()) : null;
        if (readerOpened && currentPosition != null) {
            state.putBoolean("continuousReader", true);
            state.putInt("readerPage", currentPosition.page);
            state.putFloat("readerOffset", currentPosition.offset);
        }
        if (tabs != null) state.putInt("galleryTab", tabs.getSelectedTabPosition());
        super.onSaveInstanceState(state);
    }

    @Nullable
    @Override
    public Object onRetainCustomNonConfigurationInstance() {
        if (!commentsLoaded || commentsAdapter == null || commentsList.getLayoutManager() == null) return null;
        return new RetainedComments(commentsAdapter.getComments(), commentsPage, commentsPages,
            commentsList.getLayoutManager().onSaveInstanceState());
    }

    private void applyTitle() {
        setTitle(gallery.getTitle());
        toolbar.setOnLongClickListener(v -> {
            CopyToClipboardActivity.copyTextToClipboard(this, gallery.getTitle());
            Toast.makeText(this, R.string.title_copied_to_clipboard, Toast.LENGTH_SHORT).show();
            return true;
        });
    }

    public void bindReadingActions(View header) {
        Button read = header.findViewById(R.id.gallery_read);
        Button start = header.findViewById(R.id.gallery_read_from_start);
        read.setEnabled(positionReady && gallery.getPageCount() > 0);
        read.setText(automaticPosition == null ? getString(R.string.gallery_start_reading)
            : getString(R.string.gallery_continue_page, automaticPosition.page));
        read.setOnClickListener(v -> openPagedReader(automaticPosition == null ? 1 : automaticPosition.page));
        start.setVisibility(automaticPosition == null ? View.GONE : View.VISIBLE);
        start.setOnClickListener(v -> openPagedReader(1));
    }

    private void openPagedReader(int page) {
        if (gallery.getPageCount() <= 0) return;
        if (!gallery.isLocal() && Global.getDownloadPolicy() == Global.DataUsageType.NONE
            && (adapter.getDirectory() == null || adapter.getDirectory().getPage(page) == null)) {
            Toast.makeText(this, R.string.enable_network_to_continue, Toast.LENGTH_SHORT).show();
            return;
        }
        Intent intent = new Intent(this, ZoomActivity.class);
        intent.putExtra(getPackageName() + ".GALLERY", gallery);
        intent.putExtra(getPackageName() + ".DIRECTORY", adapter.getDirectory());
        intent.putExtra(getPackageName() + ".PAGE", page);
        startActivity(intent);
    }

    private void setupTabs(@Nullable Bundle state) {
        tabs.addTab(tabs.newTab().setText(R.string.gallery_tab_details));
        tabs.addTab(tabs.newTab().setText(R.string.gallery_tab_pages));
        tabs.addTab(tabs.newTab().setText(R.string.comments));
        commentsList.setLayoutManager(new LinearLayoutManager(this));
        Object retained = getLastCustomNonConfigurationInstance();
        if (retained instanceof RetainedComments) {
            RetainedComments previous = (RetainedComments) retained;
            commentsAdapter = new CommentAdapter(this, previous.comments, true);
            commentsList.setAdapter(commentsAdapter);
            commentsList.getLayoutManager().onRestoreInstanceState(previous.layoutState);
            commentsLoaded = true;
            commentsPage = previous.page;
            commentsPages = previous.pages;
            TextView message = findViewById(R.id.comments_message);
            message.setText(R.string.gallery_comments_empty);
            message.setVisibility(commentsAdapter.getItemCount() == 0 ? View.VISIBLE : View.GONE);
            findViewById(R.id.comments_more).setVisibility(commentsPage < commentsPages ? View.VISIBLE : View.GONE);
        }
        View browser = findViewById(R.id.comments_browser);
        browser.setVisibility(!isLocal && gallery.isValid() ? View.VISIBLE : View.GONE);
        browser.setOnClickListener(v -> {
            Intent intent = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_BROWSER);
            intent.setData(Uri.parse(Utility.getBaseUrl() + "g/" + gallery.getId() + "/"));
            try {
                startActivity(intent);
            } catch (ActivityNotFoundException e) {
                Toast.makeText(this, R.string.gallery_browser_unavailable, Toast.LENGTH_SHORT).show();
            }
        });
        findViewById(R.id.gallery_jump_page).setOnClickListener(v -> {
            if (gallery.getPageCount() <= 0) return;
            DefaultDialogs.pageChangerDialog(new DefaultDialogs.Builder(this)
                .setActual(1).setMin(1).setMax(gallery.getPageCount())
                .setTitle(R.string.change_page).setDrawable(R.drawable.ic_find_in_page)
                .setDialogs(new DefaultDialogs.CustomDialogResults() {
                    @Override public void positive(int page) { recycler.scrollToPosition(page); }
                }));
        });
        commentsRefresher.setOnRefreshListener(() -> loadComments(1));
        findViewById(R.id.comments_retry).setOnClickListener(v -> loadComments(commentsRequestedPage));
        findViewById(R.id.comments_more).setOnClickListener(v -> loadComments(commentsPage + 1));
        tabs.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override public void onTabSelected(TabLayout.Tab tab) { showTab(tab.getPosition()); }
            @Override public void onTabUnselected(TabLayout.Tab tab) {
                if (tab.getPosition() == 1) {
                    saveScrollPosition();
                    stopRestoringPosition();
                }
            }
            @Override public void onTabReselected(TabLayout.Tab tab) { }
        });
        GalleryTabSwipeLayout swipeLayout = findViewById(R.id.gallery_tab_content);
        swipeLayout.setOnPageSwipeListener(new GalleryTabSwipeLayout.OnPageSwipeListener() {
            @Override public void onPageScrolled(int position, float offset) {
                tabs.setScrollPosition(position, offset, false, true);
            }

            @Override public void onPageSelected(int position) {
                selectTab(position);
            }
        });
        int selected = state == null ? 0 : state.getInt("galleryTab", 0);
        selectTab(Math.max(0, Math.min(2, selected)));
        showTab(tabs.getSelectedTabPosition());
    }

    private void selectTab(int index) {
        TabLayout.Tab tab = tabs == null ? null : tabs.getTabAt(index);
        if (tab != null) tab.select();
    }

    private void showTab(int index) {
        displayedTab = index;
        ((GalleryTabSwipeLayout) findViewById(R.id.gallery_tab_content)).setCurrentPage(index);
        if (index == 2 && !commentsLoaded && !commentsLoading) loadComments(1);
        supportInvalidateOptionsMenu();
    }

    private void loadComments(int page) {
        if (commentsLoading) return;
        TextView message = findViewById(R.id.comments_message);
        View retry = findViewById(R.id.comments_retry);
        if (isLocal || !gallery.isValid()) {
            message.setText(R.string.gallery_comments_unavailable);
            message.setVisibility(View.VISIBLE);
            retry.setVisibility(View.GONE);
            commentsRefresher.setEnabled(false);
            return;
        }
        commentsLoading = true;
        commentsRequestedPage = page;
        commentsRefresher.setRefreshing(true);
        retry.setVisibility(View.GONE);
        message.setVisibility(View.GONE);
        View more = findViewById(R.id.comments_more);
        more.setVisibility(View.GONE);
        CommentsFetcher.fetch(this, gallery.getId(), page, (comments, pages, success) -> runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;
            commentsLoading = false;
            commentsRefresher.setRefreshing(false);
            if (success) {
                commentsLoaded = true;
                commentsPage = page;
                commentsPages = pages;
                if (page == 1 || commentsAdapter == null) {
                    commentsAdapter = new CommentAdapter(this, comments, true);
                    commentsList.setAdapter(commentsAdapter);
                } else {
                    commentsAdapter.addComments(comments);
                }
                message.setText(R.string.gallery_comments_empty);
                message.setVisibility(commentsAdapter.getItemCount() == 0 ? View.VISIBLE : View.GONE);
                more.setVisibility(commentsPage < commentsPages ? View.VISIBLE : View.GONE);
            } else {
                message.setText(R.string.gallery_comments_failed);
                message.setVisibility(View.VISIBLE);
                retry.setVisibility(View.VISIBLE);
            }
        }));
    }

    @Override
    protected int getPortraitColumnCount() {
        return 0;
    }

    @Override
    protected int getLandscapeColumnCount() {
        return 0;
    }


    public void initFavoriteIcon(Menu menu) {
        boolean onlineFavorite = !isLocal && ((Gallery) gallery).isOnlineFavorite();
        boolean unknown = getIntent().getBooleanExtra(getPackageName() + ".UNKNOWN", false);
        MenuItem item = menu.findItem(R.id.add_online_gallery);

        item.setIcon(onlineFavorite ? R.drawable.ic_star : R.drawable.ic_star_border);

        if (unknown) item.setTitle(R.string.toggle_online_favorite);
        else if (onlineFavorite) item.setTitle(R.string.remove_from_online_favorites);
        else item.setTitle(R.string.add_to_online_favorite);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.gallery, menu);
        isLocalFavorite = Favorites.isFavorite(gallery);

        menu.findItem(R.id.favorite_manager).setIcon(isLocalFavorite ? R.drawable.ic_favorite : R.drawable.ic_favorite_border);
        menuItemsVisible(menu);
        menu.findItem(R.id.continue_reading).setVisible(automaticPosition != null);
        menu.findItem(R.id.manual_bookmark).setVisible(Queries.ResumeTable.pageFromId(gallery.getId()) > 0);
        initFavoriteIcon(menu);
        Utility.tintMenu(this, menu);
        updateColumnCount(false);
        return true;
    }

    private void menuItemsVisible(Menu menu) {
        boolean hasValidApiKey = AuthStore.hasValidApiKey(this);
        boolean isValidOnline = gallery.isValid() && !isLocal;
        onlineFavoriteItem = menu.findItem(R.id.add_online_gallery);
        onlineFavoriteItem.setVisible(isValidOnline && hasValidApiKey);
        menu.findItem(R.id.favorite_manager).setVisible(isValidOnline);
        menu.findItem(R.id.download_gallery).setVisible(isValidOnline);
        menu.findItem(R.id.related).setVisible(false);
        menu.findItem(R.id.comments).setVisible(false);
        menu.findItem(R.id.change_view).setVisible(tabs != null && tabs.getSelectedTabPosition() == 1);

        menu.findItem(R.id.share).setVisible(gallery.isValid());
        menu.findItem(R.id.load_internet).setVisible(isLocal && gallery.isValid());
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateColumnCount(false);
        supportInvalidateOptionsMenu();
        if (positionReady) {
            ReadingProgress.load(gallery.getId(), position -> runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                automaticPosition = position == null ? null : position.clamp(gallery.getPageCount());
                if (detailsAdapter != null) detailsAdapter.notifyItemChanged(0);
                supportInvalidateOptionsMenu();
            }));
        }
    }

    @Override
    public boolean onOptionsItemSelected(final MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.continue_reading) {
            resumeReading(automaticPosition);
        } else if (id == R.id.manual_bookmark) {
            resumeReading(new ReadingPosition(Queries.ResumeTable.pageFromId(gallery.getId()), 0f));
        } else if (id == R.id.download_gallery) {
            if (Global.hasStoragePermission(this))
                new RangeSelector(this, (Gallery) gallery).show();
            else
                requestStorage();
        } else if (id == R.id.add_online_gallery) addToFavorite();
        else if (id == R.id.change_view) updateColumnCount(true);
        else if (id == R.id.load_internet) toInternet();
        else if (id == R.id.manage_status) updateStatus();
        else if (id == R.id.share) Global.shareGallery(this, gallery);
        else if (id == R.id.favorite_manager) {
            if (isLocalFavorite) {
                Favorites.removeFavorite(gallery);
            } else {
                Favorites.addFavorite((Gallery) gallery);
            }
            isLocalFavorite = !isLocalFavorite;
            item.setIcon(isLocalFavorite ? R.drawable.ic_favorite : R.drawable.ic_favorite_border);
            Global.setTint(this, item.getIcon());
        } else if (id == android.R.id.home) {
            getOnBackPressedDispatcher().onBackPressed();
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    private void updateStatus() {
        List<String> statuses = StatusManager.getNames();
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this);
        statusString = Queries.StatusMangaTable.getStatus(gallery.getId()).name;
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.select_dialog_singlechoice, statuses) {
            @NonNull
            @Override
            public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
                CheckedTextView textView = (CheckedTextView) super.getView(position, convertView, parent);
                textView.setTextColor(StatusManager.getByName(statuses.get(position)).opaqueColor());
                return textView;
            }
        };
        builder.setSingleChoiceItems(adapter, statuses.indexOf(statusString), (dialog, which) -> statusString = statuses.get(which));
        builder
            .setNeutralButton(R.string.add, (dialog, which) -> createNewStatusDialog())
            .setNegativeButton(R.string.remove_status, (dialog, which) -> Queries.StatusMangaTable.remove(gallery.getId()))
            .setPositiveButton(R.string.ok, (dialog, which) -> Queries.StatusMangaTable.insert(gallery, statusString))
            .setTitle(R.string.change_status_title)
            .show();
    }

    private void createNewStatusDialog() {
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this);
        LinearLayout layout = (LinearLayout) View.inflate(this, R.layout.dialog_add_status, null);
        EditText name = layout.findViewById(R.id.name);
        Button btnColor = layout.findViewById(R.id.color);
        do {
            newStatusColor = Utility.RANDOM.nextInt() | 0xff000000;
        } while (newStatusColor == Color.BLACK || newStatusColor == Color.WHITE);
        btnColor.setBackgroundColor(newStatusColor);
        btnColor.setOnClickListener(v -> new AmbilWarnaDialog(GalleryActivity.this, newStatusColor, false, new AmbilWarnaDialog.OnAmbilWarnaListener() {
            @Override
            public void onCancel(AmbilWarnaDialog dialog) {
            }

            @Override
            public void onOk(AmbilWarnaDialog dialog, int color) {
                if (color == Color.WHITE || color == Color.BLACK) {
                    Toast.makeText(GalleryActivity.this, R.string.invalid_color_selected, Toast.LENGTH_SHORT).show();
                    return;
                }
                newStatusColor = color;
                btnColor.setBackgroundColor(color);
            }
        }).show());
        builder.setView(layout);
        builder.setTitle(R.string.create_new_status);
        builder.setPositiveButton(R.string.ok, (dialog, which) -> {
            String newName = name.getText().toString();
            if (newName.length() < 2) {
                Toast.makeText(this, R.string.name_too_short, Toast.LENGTH_SHORT).show();
                return;
            }
            if (StatusManager.getByName(newName) != null) {
                Toast.makeText(this, R.string.duplicated_name, Toast.LENGTH_SHORT).show();
                return;
            }
            Status status = StatusManager.add(name.getText().toString(), newStatusColor);
            Queries.StatusMangaTable.insert(gallery, status);
        });
        builder.setNegativeButton(R.string.cancel, (dialog, which) -> updateStatus());
        builder.setOnCancelListener(dialog -> updateStatus());
        builder.show();
    }

    private void updateIcon(boolean nowIsFavorite) {
        GalleryActivity.this.runOnUiThread(() -> {
            if (isFinishing() || isDestroyed() || onlineFavoriteItem == null) return;
            onlineFavoritePending = false;
            onlineFavoriteItem.setEnabled(true);
            onlineFavoriteItem.setIcon(!nowIsFavorite ? R.drawable.ic_star_border : R.drawable.ic_star);
            onlineFavoriteItem.setTitle(!nowIsFavorite ? R.string.add_to_online_favorite : R.string.remove_from_online_favorites);
        });
    }

    @Nullable
    static Boolean parseFavoriteResponse(String body) {
        if (body == null || body.trim().isEmpty()) return null;
        try {
            JSONObject response = new JSONObject(body);
            if (!response.has("favorited")) return null;
            Object value = response.get("favorited");
            return value instanceof Boolean ? (Boolean) value : null;
        } catch (JSONException exception) {
            return null;
        }
    }

    private void finishOnlineFavoriteRequest() {
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;
            onlineFavoritePending = false;
            if (onlineFavoriteItem != null) onlineFavoriteItem.setEnabled(true);
            Toast.makeText(this, R.string.unable_to_connect_to_the_site, Toast.LENGTH_LONG).show();
        });
    }

    private void addToFavorite() {
        if (onlineFavoritePending || onlineFavoriteItem == null || !(gallery instanceof Gallery)) return;
        boolean wasFavorite = Objects.equals(onlineFavoriteItem.getTitle(), getString(R.string.remove_from_online_favorites));
        String url = String.format(Locale.US, Utility.getApiBaseUrl() + "galleries/%d/favorite", gallery.getId());
        onlineFavoritePending = true;
        onlineFavoriteItem.setEnabled(false);
        try {
            Global.getClient(this)
                .newCall(new Request.Builder().url(url).method(wasFavorite ? "DELETE" : "POST", RequestBody.EMPTY).build())
                .enqueue(new Callback() {
                    @Override
                    public void onFailure(@NonNull Call call, @NonNull IOException exception) {
                        finishOnlineFavoriteRequest();
                    }

                    @Override
                    public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                        try (Response result = response) {
                            if (!result.isSuccessful() || result.body() == null) {
                                finishOnlineFavoriteRequest();
                                return;
                            }
                            Boolean favorited = parseFavoriteResponse(result.body().string());
                            if (favorited == null) finishOnlineFavoriteRequest();
                            else updateIcon(favorited);
                        }
                    }
                });
        } catch (RuntimeException exception) {
            LogUtility.w("Unable to update online favorite", exception);
            finishOnlineFavoriteRequest();
        }
    }

    private void updateColumnCount(boolean increase) {
        int x = Global.getColumnCount();
        CustomGridLayoutManager manager = (CustomGridLayoutManager) recycler.getLayoutManager();
        if (manager == null) return;
        MenuItem item = ((Toolbar) findViewById(R.id.toolbar)).getMenu().findItem(R.id.change_view);
        if (increase || manager.getSpanCount() != x) {
            if (increase) x = x % 4 + 1;
            int pos = manager.findFirstVisibleItemPosition();
            Global.updateColumnCount(this, x);

            recycler.setLayoutManager(new CustomGridLayoutManager(this, x));
            LogUtility.d("Span count: " + manager.getSpanCount());
            if (adapter != null) {
                adapter.setColCount(Global.getColumnCount());
                recycler.setAdapter(adapter);
                lookup();
                recycler.scrollToPosition(pos);

            }
        }

        if (item != null) {
            switch (x) {
                case 1:
                    item.setIcon(R.drawable.ic_view_1);
                    break;
                case 2:
                    item.setIcon(R.drawable.ic_view_2);
                    break;
                case 3:
                    item.setIcon(R.drawable.ic_view_3);
                    break;
                case 4:
                    item.setIcon(R.drawable.ic_view_4);
                    break;
            }
            Global.setTint(this, item.getIcon());

        }
    }

    private void toInternet() {
        refresher.setEnabled(true);
        InspectorV3.galleryInspector(this, gallery.getId(), new InspectorV3.DefaultInspectorResponse() {
            @Override
            public void onSuccess(List<GenericGallery> galleries) {
                if (galleries.isEmpty()) return;
                Intent intent = new Intent(GalleryActivity.this, GalleryActivity.class);
                LogUtility.d(galleries.get(0).toString());
                intent.putExtra(getPackageName() + ".GALLERY", galleries.get(0));
                runOnUiThread(() -> startActivity(intent));
            }
        }).start();
    }

    private void requestStorage() {
        requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE}, 1);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        Global.initStorage(this);
        if (requestCode == 1 && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED)
            new RangeSelector(this, (Gallery) gallery).show();
    }
}
