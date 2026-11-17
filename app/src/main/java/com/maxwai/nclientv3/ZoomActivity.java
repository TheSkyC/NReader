// Modified for NReader by TheSkyC (2026); see NOTICE.
package com.maxwai.nclientv3;

import android.Manifest;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.widget.SeekBar;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.ImageButton;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.widget.Toolbar;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.core.content.FileProvider;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.adapter.FragmentStateAdapter;
import androidx.viewpager2.widget.ViewPager2;

import com.bumptech.glide.Priority;
import com.maxwai.nclientv3.adapters.ReaderPagePreviewAdapter;
import com.maxwai.nclientv3.api.components.GenericGallery;
import com.maxwai.nclientv3.async.database.Queries;
import com.maxwai.nclientv3.async.database.ReadingProgress;
import com.maxwai.nclientv3.components.classes.ReadingPosition;
import com.maxwai.nclientv3.components.activities.GeneralActivity;
import com.maxwai.nclientv3.components.GlideX;
import com.maxwai.nclientv3.components.reader.PagedReaderNavigation;
import com.maxwai.nclientv3.components.reader.ReaderNavigation;
import com.maxwai.nclientv3.components.reader.ContinuousPageAdapter;
import com.maxwai.nclientv3.components.reader.ContinuousReaderNavigation;
import com.maxwai.nclientv3.components.reader.ContinuousZoomViewport;
import com.maxwai.nclientv3.components.reader.ReaderPageExport;
import com.maxwai.nclientv3.components.reader.ReaderPageRotations;
import com.maxwai.nclientv3.components.reader.ReaderScaleMode;
import com.maxwai.nclientv3.components.reader.SpreadReaderNavigation;
import com.maxwai.nclientv3.components.views.ZoomFragment;
import com.maxwai.nclientv3.components.views.DoublePageFragment;
import com.maxwai.nclientv3.files.GalleryFolder;
import com.maxwai.nclientv3.settings.DefaultDialogs;
import com.maxwai.nclientv3.settings.Global;
import com.maxwai.nclientv3.utility.LogUtility;
import com.maxwai.nclientv3.utility.DebugImageMask;
import com.maxwai.nclientv3.utility.Utility;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.radiobutton.MaterialRadioButton;
import com.google.android.material.switchmaterial.SwitchMaterial;

import java.io.File;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class ZoomActivity extends GeneralActivity {
    private final static int hideFlags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
        | View.SYSTEM_UI_FLAG_FULLSCREEN
        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
    private final static int showFlags = View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE;
    private static final String VOLUME_SIDE_KEY = "volumeSide";
    private static final String SCROLL_TYPE_KEY = "zoomScrollType";
    private static final String INSTANT_PAGE_TURN_KEY = "instant_page_turn";
    private static final String READER_OFFSET_STATE = "readerOffset";
    private static final String CONTINUOUS_MODE_KEY = "reader_continuous_vertical";
    private static final String READER_MODE_KEY_PREFIX = "reader_mode_";
    private static final String DOUBLE_PAGE_MODE_KEY = "reader_double_page";
    private static final String DOUBLE_PAGE_MODE_KEY_PREFIX = "reader_double_page_";
    private static final String SPREAD_MODE_KEY = "reader_spread_mode";
    private static final String SPREAD_MODE_KEY_PREFIX = "reader_spread_mode_";
    private static final int SPREAD_MODE_SINGLE = 0;
    private static final int SPREAD_MODE_DOUBLE = 1;
    private static final int SPREAD_MODE_AUTO = 2;
    private static final String PENDING_CONTINUOUS_SAVE = "pendingContinuousSave";
    private static final String PENDING_CONTINUOUS_SOURCE = "pendingContinuousSource";
    private static final String PENDING_SOURCE_IS_FILE = "pendingContinuousSourceIsFile";
    private static final float PAGE_TAP_EDGE = .2f;
    private static final String AUTO_SCROLL_SPEED_KEY = "reader_auto_scroll_speed";
    private static final String READER_BACKGROUND_KEY = "reader_background";
    private static final String READER_BACKGROUND_KEY_PREFIX = "reader_background_";
    private static final String READER_SCALE_MODE_KEY = "reader_scale_mode";
    private static final String READER_SCALE_MODE_KEY_PREFIX = "reader_scale_mode_";
    private static final int READER_BACKGROUND_AUTO = 0;
    private static final int READER_BACKGROUND_BLACK = 1;
    private static final int READER_BACKGROUND_GRAY = 2;
    private static final int READER_BACKGROUND_WHITE = 3;
    private static final int[] AUTO_SCROLL_SPEEDS = {40, 100, 220};
    private static final ExecutorService PAGE_EXPORT_EXECUTOR = Executors.newSingleThreadExecutor();
    private GenericGallery gallery;
    private int actualPage = 0;
    private ReadingPosition initialPosition;
    private boolean initializingPager = true;
    private boolean isHidden = false;
    private ViewPager2 mViewPager;
    private RecyclerView continuousReader;
    private ContinuousZoomViewport continuousZoomViewport;
    private boolean continuousMode;
    private boolean doublePageMode;
    private int spreadMode = SPREAD_MODE_SINGLE;
    private boolean switchingReaderMode;
    private ReaderNavigation readerNavigation;
    private final ReaderPageRotations continuousRotations = new ReaderPageRotations();
    private TextView pageManagerLabel, cornerPageViewer;
    private View pageSwitcher;
    private SeekBar seekBar;
    private Toolbar toolbar;
    private View view;
    private ImageButton autoScrollButton;
    private GalleryFolder directory;
    @ViewPager2.Orientation
    private int tmpScrollType;
    private boolean up = false, down = false, side;
    private int pendingContinuousSave = -1;
    @Nullable private Object pendingContinuousSource;
    @Nullable private Future<?> pageExportTask;
    private final Handler readerHandler = new Handler(Looper.getMainLooper());
    private boolean autoScrolling;
    private long autoScrollLastFrame;
    private float autoScrollRemainder;
    private int autoScrollSpeedIndex;
    private int readerScaleMode = ReaderScaleMode.SMART;
    private final Runnable autoScrollTask = new Runnable() {
        @Override
        public void run() {
            if (!autoScrolling || !continuousMode || continuousReader.getVisibility() != View.VISIBLE) return;
            long now = SystemClock.uptimeMillis();
            long elapsed = autoScrollLastFrame == 0 ? 16 : Math.min(100, Math.max(1, now - autoScrollLastFrame));
            autoScrollLastFrame = now;
            float pixels = AUTO_SCROLL_SPEEDS[autoScrollSpeedIndex] * elapsed / 1000f + autoScrollRemainder;
            int distance = (int) pixels;
            autoScrollRemainder = pixels - distance;
            int before = continuousReader.computeVerticalScrollOffset();
            if (distance > 0) continuousReader.scrollBy(0, distance);
            int after = continuousReader.computeVerticalScrollOffset();
            if ((after == before && !continuousReader.canScrollVertically(1))
                || !continuousReader.canScrollVertically(1)) {
                stopAutoScroll();
                return;
            }
            readerHandler.postDelayed(this, 16);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState != null) {
            pendingContinuousSave = savedInstanceState.getInt(PENDING_CONTINUOUS_SAVE, -1);
            String source = savedInstanceState.getString(PENDING_CONTINUOUS_SOURCE);
            if (source != null) pendingContinuousSource = savedInstanceState.getBoolean(PENDING_SOURCE_IS_FILE)
                ? new File(source) : Uri.parse(source);
        }
        //Global.initActivity(this);
        SharedPreferences preferences = getSharedPreferences("Settings", 0);
        side = preferences.getBoolean(VOLUME_SIDE_KEY, true);
        setContentView(R.layout.activity_zoom);

        //read arguments
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gallery = getIntent().getParcelableExtra(getPackageName() + ".GALLERY", GenericGallery.class);
        } else {
            gallery = getIntent().getParcelableExtra(getPackageName() + ".GALLERY");
        }
        if (gallery == null || gallery.getPageCount() <= 0) {
            finish();
            return;
        }
        continuousRotations.restore(savedInstanceState, gallery.getPageCount());
        int requestedPage = savedInstanceState == null
            ? getIntent().getIntExtra(getPackageName() + ".PAGE", 1)
            : savedInstanceState.getInt("readerPage", 1);
        float requestedOffset = savedInstanceState == null
            ? getIntent().getFloatExtra(getPackageName() + ".OFFSET", 0f)
            : savedInstanceState.getFloat(READER_OFFSET_STATE, 0f);
        initialPosition = new ReadingPosition(requestedPage, requestedOffset).clamp(gallery.getPageCount());
        final int page = initialPosition.page - 1;
        actualPage = page;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            directory = getIntent().getParcelableExtra(getPackageName() + ".DIRECTORY", GalleryFolder.class);
        } else {
            directory = getIntent().getParcelableExtra(getPackageName() + ".DIRECTORY");
        }
        if (directory == null) directory = gallery.getGalleryFolder();
        readerScaleMode = getReaderScaleMode(preferences);
        spreadMode = getSpreadMode(preferences);
        doublePageMode = isDoublePageEffective();
        //toolbar setup
        toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        ActionBar actionBar = Objects.requireNonNull(getSupportActionBar());
        actionBar.setDisplayHomeAsUpEnabled(true);
        actionBar.setDisplayShowTitleEnabled(true);
        setTitle(gallery.getTitle());

        getWindow().setFlags(
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
        if (Global.isLockScreen())
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);


        //find views
        mViewPager = findViewById(R.id.container);
        continuousReader = findViewById(R.id.continuous_reader);
        continuousZoomViewport = (ContinuousZoomViewport) continuousReader;
        mViewPager.setAdapter(createPagedAdapter());
        readerNavigation = createPagedNavigation();
        int orientation;
        try {
            orientation = preferences.getInt(SCROLL_TYPE_KEY, ViewPager2.ORIENTATION_HORIZONTAL);
        } catch (ClassCastException exception) {
            orientation = ViewPager2.ORIENTATION_HORIZONTAL;
        }
        mViewPager.setOrientation(orientation == ViewPager2.ORIENTATION_VERTICAL
            ? ViewPager2.ORIENTATION_VERTICAL : ViewPager2.ORIENTATION_HORIZONTAL);
        updateReaderDirection();
        mViewPager.setOffscreenPageLimit(Global.getOffscreenLimit());
        pageSwitcher = findViewById(R.id.page_switcher);
        pageManagerLabel = findViewById(R.id.pages);
        cornerPageViewer = findViewById(R.id.page_text);
        seekBar = findViewById(R.id.seekBar);
        view = findViewById(R.id.view);
        autoScrollButton = findViewById(R.id.reader_auto_scroll);
        try {
            autoScrollSpeedIndex = Math.max(0, Math.min(AUTO_SCROLL_SPEEDS.length - 1,
                preferences.getInt(AUTO_SCROLL_SPEED_KEY, 1)));
        } catch (ClassCastException exception) {
            autoScrollSpeedIndex = 1;
        }
        autoScrollButton.setOnClickListener(v -> toggleAutoScroll());
        autoScrollButton.setOnLongClickListener(v -> {
            showAutoScrollSpeedDialog();
            return true;
        });
        autoScrollButton.setVisibility(View.GONE);
        continuousZoomViewport.setOnUserInteractionListener(this::stopAutoScroll);
        applyReaderBackground();

        //initial setup for views
        changeLayout(getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE);
        mViewPager.setKeepScreenOn(Global.isLockScreen());
        continuousReader.setKeepScreenOn(Global.isLockScreen());
        findViewById(R.id.prev).setOnClickListener(v -> changeClosePage(false));
        findViewById(R.id.next).setOnClickListener(v -> changeClosePage(true));
        findViewById(R.id.reader_page_preview).setOnClickListener(v -> showPagePreview());
        findViewById(R.id.reader_settings).setOnClickListener(v -> showReaderSettings());
        seekBar.setMax(gallery.getPageCount() - 1);
        if (Global.useRtl()) {
            seekBar.setRotationY(180);
            mViewPager.setLayoutDirection(ViewPager2.LAYOUT_DIRECTION_RTL);
        }

        //Adding listeners
        mViewPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int newPage) {
                if (continuousMode || switchingReaderMode) return;
                int oldPage = actualPage;
                actualPage = readerNavigation.currentPage();
                if (!initializingPager && initialPosition.page != actualPage + 1) {
                    initialPosition = new ReadingPosition(actualPage + 1, 0f);
                }
                LogUtility.d("Page selected: " + actualPage + " from page " + oldPage);
                setPageText(actualPage + 1);
                seekBar.setProgress(actualPage);
                invalidateOptionsMenu();
                clearFarRequests(oldPage, actualPage);
                makeNearRequests(actualPage);
                if (!initializingPager) savePosition();
            }
        });
        pageManagerLabel.setOnClickListener(v -> DefaultDialogs.pageChangerDialog(
            new DefaultDialogs.Builder(this)
                .setActual(actualPage + 1)
                .setMin(1)
                .setMax(gallery.getPageCount())
                .setTitle(R.string.change_page)
                .setDrawable(R.drawable.ic_find_in_page)
                .setDialogs(new DefaultDialogs.CustomDialogResults() {
                    @Override
                    public void positive(int actual) {
                        changePage(actual - 1);
                    }
                })
        ));
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    setPageText(progress + 1);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                changePage(seekBar.getProgress());
            }
        });


        readerNavigation.goToPage(page, false);
        initializingPager = false;
        setPageText(page + 1);
        seekBar.setProgress(page);
        boolean continuous = isContinuousModeEnabled(preferences);
        if (continuous) setContinuousMode(true, page);
    }

    private FragmentStateAdapter createPagedAdapter() {
        return doublePageMode ? new DoublePagePagerAdapter(this) : new SectionsPagerAdapter(this);
    }

    private ReaderNavigation createPagedNavigation() {
        return doublePageMode ? new SpreadReaderNavigation(mViewPager, gallery.getPageCount())
            : new PagedReaderNavigation(mViewPager);
    }

    private void setContinuousMode(boolean enabled) {
        if (enabled && !continuousMode)
            initialPosition = new ReadingPosition(readerNavigation.currentPage() + 1, 0f);
        setContinuousMode(enabled, readerNavigation.currentPage());
    }

    private void setContinuousMode(boolean enabled, int page) {
        if (continuousMode == enabled) return;
        switchingReaderMode = true;
        continuousMode = enabled;
        stopAutoScroll();
        continuousZoomViewport.resetZoom();
        if (enabled) {
            mViewPager.setAdapter(null);
            LinearLayoutManager layout = new LinearLayoutManager(this);
            continuousReader.setLayoutManager(layout);
            ContinuousPageAdapter adapter = new ContinuousPageAdapter(gallery, directory,
                (ignored, horizontalPosition) -> handleReaderPageTap(horizontalPosition), continuousRotations);
            adapter.setOnPageDoubleTapListener((ignored, x, y) -> continuousZoomViewport.toggleDoubleTapZoom(x, y));
            continuousReader.setAdapter(adapter);
            readerNavigation = new ContinuousReaderNavigation(continuousReader, layout, page, this::onContinuousPageChanged);
            mViewPager.setVisibility(View.GONE);
            continuousZoomViewport.setVisibility(View.VISIBLE);
            continuousReader.setVisibility(View.VISIBLE);
            autoScrollButton.setVisibility(View.VISIBLE);
            ((ContinuousReaderNavigation) readerNavigation).goToPosition(initialPosition);
        } else {
            ((ContinuousReaderNavigation) readerNavigation).dispose();
            initialPosition = new ReadingPosition(page + 1, 0f);
            continuousZoomViewport.setVisibility(View.GONE);
            continuousReader.setVisibility(View.GONE);
            autoScrollButton.setVisibility(View.GONE);
            continuousReader.setAdapter(null);
            continuousReader.clearOnScrollListeners();
            mViewPager.setAdapter(createPagedAdapter());
            readerNavigation = createPagedNavigation();
            mViewPager.setVisibility(View.VISIBLE);
            readerNavigation.goToPage(page, false);
        }
        actualPage = page;
        setPageText(page + 1);
        seekBar.setProgress(page);
        switchingReaderMode = false;
        getSharedPreferences("Settings", 0).edit().putBoolean(readerModePreferenceKey(), enabled).apply();
    }

    private void setDoublePageMode(boolean enabled) {
        setSpreadMode(enabled ? SPREAD_MODE_DOUBLE : SPREAD_MODE_SINGLE);
    }

    private void setSpreadMode(int mode) {
        if (continuousMode) setContinuousMode(false);
        spreadMode = mode < SPREAD_MODE_SINGLE || mode > SPREAD_MODE_AUTO ? SPREAD_MODE_SINGLE : mode;
        boolean enabled = isDoublePageEffective();
        if (doublePageMode == enabled) {
            getSharedPreferences("Settings", 0).edit().putInt(spreadModePreferenceKey(), spreadMode).apply();
            return;
        }
        applyDoublePageMode(enabled);
        getSharedPreferences("Settings", 0).edit().putInt(spreadModePreferenceKey(), spreadMode).apply();
    }

    private void applyDoublePageMode(boolean enabled) {
        if (doublePageMode == enabled) return;
        switchingReaderMode = true;
        int page = readerNavigation.currentPage();
        doublePageMode = enabled;
        mViewPager.setAdapter(createPagedAdapter());
        readerNavigation = createPagedNavigation();
        readerNavigation.goToPage(page, false);
        actualPage = readerNavigation.currentPage();
        initialPosition = new ReadingPosition(actualPage + 1, 0f);
        mViewPager.setVisibility(View.VISIBLE);
        setPageText(actualPage + 1);
        seekBar.setProgress(actualPage);
        switchingReaderMode = false;
    }

    private boolean isContinuousModeEnabled(@NonNull SharedPreferences preferences) {
        String key = readerModePreferenceKey();
        if (preferences.contains(key)) {
            try {
                return preferences.getBoolean(key, false);
            } catch (ClassCastException exception) {
                return false;
            }
        }
        try {
            return preferences.getBoolean(CONTINUOUS_MODE_KEY, false);
        } catch (ClassCastException exception) {
            return false;
        }
    }

    private int getSpreadMode(@NonNull SharedPreferences preferences) {
        String key = spreadModePreferenceKey();
        if (preferences.contains(key)) {
            try {
                int value = preferences.getInt(key, SPREAD_MODE_SINGLE);
                return value >= SPREAD_MODE_SINGLE && value <= SPREAD_MODE_AUTO ? value : SPREAD_MODE_SINGLE;
            } catch (ClassCastException exception) {
                return SPREAD_MODE_SINGLE;
            }
        }
        String legacyKey = doublePagePreferenceKey();
        if (preferences.contains(legacyKey)) {
            try {
                return preferences.getBoolean(legacyKey, false) ? SPREAD_MODE_DOUBLE : SPREAD_MODE_SINGLE;
            } catch (ClassCastException exception) {
                return SPREAD_MODE_SINGLE;
            }
        }
        return SPREAD_MODE_SINGLE;
    }

    private boolean isDoublePageEffective() {
        return spreadMode == SPREAD_MODE_DOUBLE
            || (spreadMode == SPREAD_MODE_AUTO
                && getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE);
    }

    @NonNull
    private String spreadModePreferenceKey() {
        return gallery != null && gallery.getId() > 0
            ? SPREAD_MODE_KEY_PREFIX + gallery.getId() : SPREAD_MODE_KEY;
    }

    @NonNull
    private String doublePagePreferenceKey() {
        return gallery != null && gallery.getId() > 0
            ? DOUBLE_PAGE_MODE_KEY_PREFIX + gallery.getId() : DOUBLE_PAGE_MODE_KEY;
    }

    @NonNull
    private String readerModePreferenceKey() {
        return gallery != null && gallery.getId() > 0
            ? READER_MODE_KEY_PREFIX + gallery.getId() : CONTINUOUS_MODE_KEY;
    }

    private void onContinuousPageChanged(int page) {
        if (!continuousMode) return;
        actualPage = page;
        setPageText(page + 1);
        seekBar.setProgress(page);
        invalidateOptionsMenu();
        if (!switchingReaderMode) savePosition();
    }

    public void onReaderPageTap(ZoomFragment source, float horizontalPosition) {
        if (!isCurrentPagedFragment(source)) return;
        if (doublePageMode && !continuousMode) {
            SpreadReaderNavigation spread = (SpreadReaderNavigation) readerNavigation;
            spread.selectPage(source.getPageIndex());
            actualPage = readerNavigation.currentPage();
            setPageText(actualPage + 1);
            seekBar.setProgress(actualPage);
            invalidateOptionsMenu();
        }
        handleReaderPageTap(horizontalPosition);
    }

    private boolean isCurrentPagedFragment(@NonNull ZoomFragment source) {
        if (continuousMode) return false;
        if (!doublePageMode) return source == getActualFragment();
        int current = readerNavigation.currentPage();
        return source.getPageIndex() == current || source.getPageIndex() == current + 1;
    }

    private void handleReaderPageTap(float horizontalPosition) {
        if (Global.isButtonChangePage() && (horizontalPosition < PAGE_TAP_EDGE
            || horizontalPosition > 1f - PAGE_TAP_EDGE)) {
            changeClosePage(horizontalPosition > 1f - PAGE_TAP_EDGE);
        } else {
            isHidden = !isHidden;
            applyVisibilityFlag();
            animateLayout();
        }
    }

    public boolean isVerticalPageTurn() {
        return mViewPager != null && mViewPager.getOrientation() == ViewPager2.ORIENTATION_VERTICAL;
    }

    private void updateReaderDirection() {
        for (Fragment fragment : getSupportFragmentManager().getFragments()) {
            if (fragment instanceof ZoomFragment) {
                ((ZoomFragment) fragment).setVerticalPageTurn(isVerticalPageTurn());
            } else if (fragment instanceof DoublePageFragment) {
                DoublePageFragment spread = (DoublePageFragment) fragment;
                ZoomFragment first = spread.pageFragment(spread.firstPage());
                if (first != null) first.setVerticalPageTurn(isVerticalPageTurn());
                if (spread.secondPage() >= 0) {
                    ZoomFragment second = spread.pageFragment(spread.secondPage());
                    if (second != null) second.setVerticalPageTurn(isVerticalPageTurn());
                }
            }
        }
    }

    private void savePosition() {
        if (gallery != null && readerNavigation != null && initialPosition != null) {
            ReadingPosition position = currentPosition();
            if (position != null) {
                initialPosition = position;
                ReadingProgress.save(gallery.getId(), position);
            }
        }
    }

    @Nullable
    private ReadingPosition currentPosition() {
        if (continuousMode) return ((ContinuousReaderNavigation) readerNavigation).currentPosition();
        int page = readerNavigation.currentPage() + 1;
        return initialPosition.page == page ? initialPosition : new ReadingPosition(page, 0f);
    }

    @Override
    protected void onPause() {
        stopAutoScroll();
        savePosition();
        super.onPause();
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle state) {
        continuousRotations.save(state);
        state.putInt(PENDING_CONTINUOUS_SAVE, pendingContinuousSave);
        if (pendingContinuousSource != null) state.putString(PENDING_CONTINUOUS_SOURCE, pendingContinuousSource.toString());
        state.putBoolean(PENDING_SOURCE_IS_FILE, pendingContinuousSource instanceof File);
        if (readerNavigation != null) {
            ReadingPosition position = currentPosition();
            if (position == null) position = initialPosition;
            state.putInt("readerPage", position.page);
            state.putFloat(READER_OFFSET_STATE, position.offset);
        }
        savePosition();
        super.onSaveInstanceState(state);
    }

    @Override
    protected void onDestroy() {
        stopAutoScroll();
        continuousZoomViewport.setOnUserInteractionListener(null);
        if (pageExportTask != null) pageExportTask.cancel(true);
        super.onDestroy();
    }

    private void setPageText(int page) {
        pageManagerLabel.setText(getString(R.string.page_format, page, gallery.getPageCount()));
        cornerPageViewer.setText(getString(R.string.page_format, page, gallery.getPageCount()));
    }


    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (Global.volumeOverride()) {
            switch (keyCode) {
                case KeyEvent.KEYCODE_VOLUME_UP:
                    up = false;
                    return true;
                case KeyEvent.KEYCODE_VOLUME_DOWN:
                    down = false;
                    return true;
            }
        }
        return super.onKeyUp(keyCode, event);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (Global.volumeOverride()) {
            switch (keyCode) {
                case KeyEvent.KEYCODE_VOLUME_UP:
                    up = true;
                    changeClosePage(side);
                    if (up && down) changeSide();
                    return true;
                case KeyEvent.KEYCODE_VOLUME_DOWN:
                    down = true;
                    changeClosePage(!side);
                    if (up && down) changeSide();
                    return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    private void changeSide() {
        getSharedPreferences("Settings", 0).edit().putBoolean(VOLUME_SIDE_KEY, side = !side).apply();
        Toast.makeText(this, side ? R.string.next_page_volume_up : R.string.next_page_volume_down, Toast.LENGTH_SHORT).show();
    }

    public void changeClosePage(boolean next) {
        if (!continuousMode && Global.useRtl()) next = !next;
        int current = readerNavigation.currentPage();
        if (next && current < readerNavigation.pageCount() - 1) changePage(current + 1);
        if (!next && current > 0) changePage(current - 1);
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        stopAutoScroll();
        ReadingPosition position = continuousMode ? currentPosition() : null;
        super.onConfigurationChanged(newConfig);
        changeLayout(newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE);
        if (!continuousMode && spreadMode == SPREAD_MODE_AUTO) {
            boolean desiredDoublePage = isDoublePageEffective();
            if (desiredDoublePage != doublePageMode) applyDoublePageMode(desiredDoublePage);
        }
        if (continuousMode) continuousZoomViewport.resetZoom();
        else applyReaderScaleMode();
        if (position != null) ((ContinuousReaderNavigation) readerNavigation).goToPosition(position);
    }

    private boolean hardwareKeys() {
        return ViewConfiguration.get(this).hasPermanentMenuKey();
    }

    private void applyMargin(boolean landscape, View view) {
        ConstraintLayout.LayoutParams lp = (ConstraintLayout.LayoutParams) view.getLayoutParams();
        lp.setMargins(0, 0, landscape && !hardwareKeys() ? Global.getNavigationBarHeight(this) : 0, 0);
        view.setLayoutParams(lp);
    }

    private void changeLayout(boolean landscape) {
        int statusBarHeight = Global.getStatusBarHeight(this);
        applyMargin(landscape, findViewById(R.id.master_layout));
        applyMargin(landscape, toolbar);
        pageSwitcher.setPadding(0, 0, 0, landscape ? 0 : statusBarHeight);
    }

    private void changePage(int newPage) {
        boolean instant = isInstantPageTurn(getSharedPreferences("Settings", 0));
        readerNavigation.goToPage(newPage, !instant);
        if (doublePageMode && !continuousMode) {
            actualPage = readerNavigation.currentPage();
            setPageText(actualPage + 1);
            seekBar.setProgress(actualPage);
            invalidateOptionsMenu();
            if (!switchingReaderMode) savePosition();
        }
    }

    private boolean isInstantPageTurn(SharedPreferences preferences) {
        try {
            return preferences.getBoolean(INSTANT_PAGE_TURN_KEY, false);
        } catch (ClassCastException exception) {
            return false;
        }
    }

    private void changeScrollTypeDialog() {
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this);
        int scrollType = mViewPager.getOrientation();
        tmpScrollType = mViewPager.getOrientation();
        builder.setTitle(getString(R.string.change_scroll_type) + ":");
        builder.setSingleChoiceItems(R.array.scroll_type, scrollType, (dialog, which) -> tmpScrollType = which);
        builder.setPositiveButton(R.string.ok, (dialog, which) -> {
            if (continuousMode || tmpScrollType != scrollType) {
                setReaderOrientation(tmpScrollType);
            }
        }).setNegativeButton(R.string.cancel, null);
        builder.show();
    }

    private void setReaderOrientation(@ViewPager2.Orientation int orientation) {
        if (continuousMode) setContinuousMode(false);
        if (mViewPager.getOrientation() == orientation) return;
        mViewPager.setOrientation(orientation);
        updateReaderDirection();
        getSharedPreferences("Settings", 0).edit().putInt(SCROLL_TYPE_KEY, orientation).apply();
    }

    private void showPagePreview() {
        BottomSheetDialog dialog = new BottomSheetDialog(this);
        dialog.setContentView(R.layout.reader_page_preview_sheet);
        RecyclerView grid = Objects.requireNonNull(dialog.findViewById(R.id.reader_page_grid));
        GridLayoutManager layoutManager = new GridLayoutManager(this, 2);
        grid.setLayoutManager(layoutManager);
        boolean[] firstLayout = {true};
        grid.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            int columns = Math.max(2, Math.min(6,
                (int) ((right - left) / getResources().getDisplayMetrics().density / 120)));
            if (columns != layoutManager.getSpanCount()) {
                int first = firstLayout[0] ? readerNavigation.currentPage() : layoutManager.findFirstVisibleItemPosition();
                layoutManager.setSpanCount(columns);
                grid.scrollToPosition(first == RecyclerView.NO_POSITION ? readerNavigation.currentPage() : first);
            }
            firstLayout[0] = false;
        });
        grid.setAdapter(new ReaderPagePreviewAdapter(gallery, directory, readerNavigation.currentPage(), page -> {
            changePage(page);
            dialog.dismiss();
        }));
        grid.scrollToPosition(readerNavigation.currentPage());
        dialog.setOnDismissListener(ignored -> {
            for (int index = 0; index < grid.getChildCount(); index++) {
                View image = grid.getChildAt(index).findViewById(R.id.reader_preview_image);
                com.bumptech.glide.RequestManager glide = GlideX.with(image);
                if (glide != null) glide.clear(image);
            }
            grid.setAdapter(null);
        });
        dialog.setOnShowListener(ignored -> dialog.getBehavior().setState(BottomSheetBehavior.STATE_EXPANDED));
        dialog.show();
    }

    private void showReaderSettings() {
        BottomSheetDialog dialog = new BottomSheetDialog(this);
        dialog.setContentView(R.layout.reader_settings_sheet);
        String[] directions = getResources().getStringArray(R.array.scroll_type);
        MaterialRadioButton horizontal = Objects.requireNonNull(dialog.findViewById(R.id.reader_horizontal));
        MaterialRadioButton vertical = Objects.requireNonNull(dialog.findViewById(R.id.reader_vertical));
        horizontal.setText(directions[ViewPager2.ORIENTATION_HORIZONTAL]);
        vertical.setText(directions[ViewPager2.ORIENTATION_VERTICAL]);
        RadioGroup direction = Objects.requireNonNull(dialog.findViewById(R.id.reader_direction));
        direction.check(continuousMode ? R.id.reader_continuous
            : isVerticalPageTurn() ? R.id.reader_vertical : R.id.reader_horizontal);
        direction.setOnCheckedChangeListener((group, id) -> {
            if (id == R.id.reader_horizontal) setReaderOrientation(ViewPager2.ORIENTATION_HORIZONTAL);
            else if (id == R.id.reader_vertical) setReaderOrientation(ViewPager2.ORIENTATION_VERTICAL);
            else if (id == R.id.reader_continuous) setContinuousMode(true);
        });

        MaterialRadioButton spreadSingle = Objects.requireNonNull(dialog.findViewById(R.id.reader_spread_single));
        MaterialRadioButton spreadDouble = Objects.requireNonNull(dialog.findViewById(R.id.reader_spread_double));
        MaterialRadioButton spreadAuto = Objects.requireNonNull(dialog.findViewById(R.id.reader_spread_auto));
        spreadSingle.setText(R.string.reader_spread_single);
        spreadDouble.setText(R.string.reader_spread_double);
        spreadAuto.setText(R.string.reader_spread_auto);
        RadioGroup spread = Objects.requireNonNull(dialog.findViewById(R.id.reader_spread_mode));
        spread.check(spreadId(spreadMode));
        spread.setOnCheckedChangeListener((group, id) -> setSpreadMode(
            id == R.id.reader_spread_double ? SPREAD_MODE_DOUBLE
                : id == R.id.reader_spread_auto ? SPREAD_MODE_AUTO : SPREAD_MODE_SINGLE));

        MaterialRadioButton scaleSmart = Objects.requireNonNull(dialog.findViewById(R.id.reader_scale_smart));
        MaterialRadioButton scaleScreen = Objects.requireNonNull(dialog.findViewById(R.id.reader_scale_screen));
        MaterialRadioButton scaleWidth = Objects.requireNonNull(dialog.findViewById(R.id.reader_scale_width));
        MaterialRadioButton scaleOriginal = Objects.requireNonNull(dialog.findViewById(R.id.reader_scale_original));
        scaleSmart.setText(R.string.reader_scale_smart);
        scaleScreen.setText(R.string.reader_scale_screen);
        scaleWidth.setText(R.string.reader_scale_width);
        scaleOriginal.setText(R.string.reader_scale_original);
        RadioGroup scale = Objects.requireNonNull(dialog.findViewById(R.id.reader_scale));
        scale.check(scaleId(readerScaleMode));
        scale.setOnCheckedChangeListener((group, id) -> setReaderScaleMode(
            id == R.id.reader_scale_screen ? ReaderScaleMode.FIT_SCREEN
                : id == R.id.reader_scale_width ? ReaderScaleMode.FIT_WIDTH
                : id == R.id.reader_scale_original ? ReaderScaleMode.ORIGINAL : ReaderScaleMode.SMART));

        MaterialRadioButton backgroundAuto = Objects.requireNonNull(dialog.findViewById(R.id.reader_background_auto));
        MaterialRadioButton backgroundBlack = Objects.requireNonNull(dialog.findViewById(R.id.reader_background_black));
        MaterialRadioButton backgroundGray = Objects.requireNonNull(dialog.findViewById(R.id.reader_background_gray));
        MaterialRadioButton backgroundWhite = Objects.requireNonNull(dialog.findViewById(R.id.reader_background_white));
        backgroundAuto.setText(R.string.reader_background_auto);
        backgroundBlack.setText(R.string.reader_background_black);
        backgroundGray.setText(R.string.reader_background_gray);
        backgroundWhite.setText(R.string.reader_background_white);
        RadioGroup background = Objects.requireNonNull(dialog.findViewById(R.id.reader_background));
        background.check(backgroundId(getReaderBackground()));
        background.setOnCheckedChangeListener((group, id) -> {
            int value = id == R.id.reader_background_black ? READER_BACKGROUND_BLACK
                : id == R.id.reader_background_gray ? READER_BACKGROUND_GRAY
                : id == R.id.reader_background_white ? READER_BACKGROUND_WHITE : READER_BACKGROUND_AUTO;
            getSharedPreferences("Settings", 0).edit().putInt(readerBackgroundPreferenceKey(), value).apply();
            applyReaderBackground();
        });

        SwitchMaterial tap = Objects.requireNonNull(dialog.findViewById(R.id.reader_tap_turn));
        tap.setChecked(Global.isButtonChangePage());
        tap.setOnCheckedChangeListener((button, checked) -> Global.setButtonChangePage(this, checked));
        SwitchMaterial volume = Objects.requireNonNull(dialog.findViewById(R.id.reader_volume_turn));
        volume.setChecked(Global.volumeOverride());
        volume.setOnCheckedChangeListener((button, checked) -> {
            up = false;
            down = false;
            Global.setVolumeOverride(this, checked);
        });
        SharedPreferences preferences = getSharedPreferences("Settings", 0);
        SwitchMaterial instant = Objects.requireNonNull(dialog.findViewById(R.id.reader_instant_turn));
        instant.setChecked(isInstantPageTurn(preferences));
        instant.setOnCheckedChangeListener((button, checked) ->
            preferences.edit().putBoolean(INSTANT_PAGE_TURN_KEY, checked).apply());
        dialog.setOnShowListener(ignored -> dialog.getBehavior().setState(BottomSheetBehavior.STATE_EXPANDED));
        dialog.show();
    }

    private int getReaderBackground() {
        SharedPreferences preferences = getSharedPreferences("Settings", 0);
        String key = readerBackgroundPreferenceKey();
        if (preferences.contains(key)) {
            try {
                int value = preferences.getInt(key, READER_BACKGROUND_AUTO);
                return value >= READER_BACKGROUND_AUTO && value <= READER_BACKGROUND_WHITE
                    ? value : READER_BACKGROUND_AUTO;
            } catch (ClassCastException exception) {
                return READER_BACKGROUND_AUTO;
            }
        }
        try {
            int value = preferences.getInt(READER_BACKGROUND_KEY, READER_BACKGROUND_AUTO);
            return value >= READER_BACKGROUND_AUTO && value <= READER_BACKGROUND_WHITE ? value : READER_BACKGROUND_AUTO;
        } catch (ClassCastException exception) {
            return READER_BACKGROUND_AUTO;
        }
    }

    @NonNull
    private String readerBackgroundPreferenceKey() {
        return gallery != null && gallery.getId() > 0
            ? READER_BACKGROUND_KEY_PREFIX + gallery.getId() : READER_BACKGROUND_KEY;
    }

    private int getReaderScaleMode(@NonNull SharedPreferences preferences) {
        String key = readerScaleModePreferenceKey();
        if (preferences.contains(key)) {
            try {
                return ReaderScaleMode.sanitize(preferences.getInt(key, ReaderScaleMode.SMART));
            } catch (ClassCastException exception) {
                return ReaderScaleMode.SMART;
            }
        }
        try {
            return ReaderScaleMode.sanitize(preferences.getInt(READER_SCALE_MODE_KEY, ReaderScaleMode.SMART));
        } catch (ClassCastException exception) {
            return ReaderScaleMode.SMART;
        }
    }

    @NonNull
    private String readerScaleModePreferenceKey() {
        return gallery != null && gallery.getId() > 0
            ? READER_SCALE_MODE_KEY_PREFIX + gallery.getId() : READER_SCALE_MODE_KEY;
    }

    private void setReaderScaleMode(int mode) {
        readerScaleMode = ReaderScaleMode.sanitize(mode);
        getSharedPreferences("Settings", 0).edit()
            .putInt(readerScaleModePreferenceKey(), readerScaleMode).apply();
        applyReaderScaleMode();
    }

    private int backgroundId(int value) {
        return value == READER_BACKGROUND_BLACK ? R.id.reader_background_black
            : value == READER_BACKGROUND_GRAY ? R.id.reader_background_gray
            : value == READER_BACKGROUND_WHITE ? R.id.reader_background_white : R.id.reader_background_auto;
    }

    private int scaleId(int value) {
        return value == ReaderScaleMode.FIT_SCREEN ? R.id.reader_scale_screen
            : value == ReaderScaleMode.FIT_WIDTH ? R.id.reader_scale_width
            : value == ReaderScaleMode.ORIGINAL ? R.id.reader_scale_original : R.id.reader_scale_smart;
    }

    private int spreadId(int value) {
        return value == SPREAD_MODE_DOUBLE ? R.id.reader_spread_double
            : value == SPREAD_MODE_AUTO ? R.id.reader_spread_auto : R.id.reader_spread_single;
    }

    private void applyReaderBackground() {
        int color = getReaderBackground() == READER_BACKGROUND_BLACK ? android.R.color.black
            : getReaderBackground() == READER_BACKGROUND_GRAY ? R.color.theme_reader_gray_background
            : getReaderBackground() == READER_BACKGROUND_WHITE ? android.R.color.white
            : R.color.theme_reader_background;
        int resolved = getColor(color);
        mViewPager.setBackgroundColor(resolved);
        continuousReader.setBackgroundColor(resolved);
    }

    private void applyReaderScaleMode() {
        for (Fragment fragment : getSupportFragmentManager().getFragments()) {
            if (fragment instanceof ZoomFragment) ((ZoomFragment) fragment).setScaleMode(readerScaleMode);
        }
    }

    private void toggleAutoScroll() {
        if (!continuousMode) return;
        if (autoScrolling) stopAutoScroll();
        else startAutoScroll();
    }

    private void startAutoScroll() {
        if (!continuousMode || autoScrolling || !continuousReader.canScrollVertically(1)) return;
        autoScrolling = true;
        autoScrollLastFrame = 0;
        autoScrollRemainder = 0f;
        autoScrollButton.setImageResource(R.drawable.ic_pause);
        autoScrollButton.setContentDescription(getString(R.string.reader_auto_scroll_stop));
        readerHandler.post(autoScrollTask);
    }

    private void stopAutoScroll() {
        if (!autoScrolling) return;
        autoScrolling = false;
        autoScrollLastFrame = 0;
        autoScrollRemainder = 0f;
        readerHandler.removeCallbacks(autoScrollTask);
        if (autoScrollButton != null) {
            autoScrollButton.setImageResource(R.drawable.ic_play);
            autoScrollButton.setContentDescription(getString(R.string.reader_auto_scroll_start));
        }
    }

    private void showAutoScrollSpeedDialog() {
        new MaterialAlertDialogBuilder(this)
            .setTitle(R.string.reader_auto_scroll_speed)
            .setSingleChoiceItems(R.array.reader_auto_scroll_speeds, autoScrollSpeedIndex, (dialog, which) -> {
                autoScrollSpeedIndex = Math.max(0, Math.min(AUTO_SCROLL_SPEEDS.length - 1, which));
                getSharedPreferences("Settings", 0).edit().putInt(AUTO_SCROLL_SPEED_KEY, autoScrollSpeedIndex).apply();
                dialog.dismiss();
            })
            .show();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_zoom, menu);
        Utility.tintMenu(this, menu);
        updateBookmarkMenu(menu.findItem(R.id.bookmark));
        return true;
    }

    private void updateBookmarkMenu(@Nullable MenuItem item) {
        if (item == null || gallery == null) return;
        boolean bookmarked = gallery.getId() > 0
            && Queries.ResumeTable.pageFromId(gallery.getId()) == readerNavigation.currentPage() + 1;
        item.setIcon(bookmarked ? R.drawable.ic_bookmark : R.drawable.ic_bookmark_border);
    }

    private void toggleCurrentBookmark() {
        if (gallery == null || gallery.getId() <= 0 || readerNavigation == null) return;
        int id = gallery.getId();
        int page = readerNavigation.currentPage() + 1;
        if (Queries.ResumeTable.pageFromId(id) == page) Queries.ResumeTable.remove(id);
        else Queries.ResumeTable.insert(id, page);
        invalidateOptionsMenu();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();
        if (id == R.id.rotate) {
            if (continuousMode) {
                int page = readerNavigation.currentPage();
                RecyclerView.ViewHolder holder = continuousReader.findViewHolderForAdapterPosition(page);
                RecyclerView.Adapter<?> adapter = continuousReader.getAdapter();
                ReadingPosition position = currentPosition();
                if (holder == null || position == null || !(adapter instanceof ContinuousPageAdapter)
                    || !((ContinuousPageAdapter) adapter).rotate(holder)) showPageUnavailable();
                else ((ContinuousReaderNavigation) readerNavigation).goToPosition(position);
            } else {
                ZoomFragment fragment = getActualFragment();
                if (fragment == null || fragment.getView() == null) showPageUnavailable();
                else fragment.rotate();
            }
        } else if (id == R.id.save_page) {
            if (continuousMode) {
                int page = readerNavigation.currentPage();
                Object source = continuousPageSource(page);
                if (source == null) showPageUnavailable();
                else confirmOriginalExport(page, () -> saveContinuousPage(page, source));
            } else if (getAvailablePageImage() == null) {
                showPageUnavailable();
            } else if (Global.hasStoragePermission(this)) {
                downloadPage();
            } else requestStorage();
        } else if (id == R.id.share) {
            if (continuousMode) {
                int page = readerNavigation.currentPage();
                Object source = continuousPageSource(page);
                if (source == null) showPageUnavailable();
                else confirmOriginalExport(page, () -> {
                    if (gallery.getId() <= 0) exportContinuousPage(page, source, true, false);
                    else openContinuousShareDialog(page, source);
                });
            } else if (getAvailablePageImage() == null) showPageUnavailable();
            else if (gallery.getId() <= 0) sendImage(false);
            else openSendImageDialog();
        } else if (id == android.R.id.home) {
            finish();
            return true;
        } else if (id == R.id.bookmark) {
            toggleCurrentBookmark();
        } else if (id == R.id.scrollType) {
            changeScrollTypeDialog();
        }

        return super.onOptionsItemSelected(item);
    }

    private void openSendImageDialog() {
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this);
        builder.setPositiveButton(R.string.yes, (dialog, which) -> sendImage(true))
            .setNegativeButton(R.string.no, (dialog, which) -> sendImage(false))
            .setCancelable(true).setTitle(R.string.send_with_title)
            .setMessage(R.string.caption_send_with_title)
            .show();
    }

    private void confirmOriginalExport(int page, @NonNull Runnable action) {
        if (continuousRotations.angle(page) == 0) {
            action.run();
            return;
        }
        new MaterialAlertDialogBuilder(this)
            .setMessage(R.string.reader_export_original_rotation)
            .setPositiveButton(R.string.yes, (dialog, which) -> action.run())
            .setNegativeButton(R.string.no, null)
            .show();
    }

    private void saveContinuousPage(int page, @NonNull Object source) {
        if (Global.hasStoragePermission(this)) exportContinuousPage(page, source, false, false);
        else {
            pendingContinuousSave = page;
            pendingContinuousSource = source;
            requestStorage();
        }
    }

    private void openContinuousShareDialog(int page, @NonNull Object source) {
        new MaterialAlertDialogBuilder(this)
            .setPositiveButton(R.string.yes, (dialog, which) -> exportContinuousPage(page, source, true, true))
            .setNegativeButton(R.string.no, (dialog, which) -> exportContinuousPage(page, source, true, false))
            .setCancelable(true).setTitle(R.string.send_with_title)
            .setMessage(R.string.caption_send_with_title)
            .show();
    }

    private void requestStorage() {
        requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE}, 1);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        Global.initStorage(this);
        if (requestCode == 1) {
            int page = pendingContinuousSave;
            Object source = pendingContinuousSource;
            pendingContinuousSave = -1;
            pendingContinuousSource = null;
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                if (page >= 0 && source != null) exportContinuousPage(page, source, false, false);
                else if (page >= 0) showPageUnavailable();
                else downloadPage();
            }
        }

    }

    private ZoomFragment getActualFragment() {
        return getActualFragment(readerNavigation.currentPage());
    }

    private void makeNearRequests(int newPage) {
        ZoomFragment fragment;
        int offScreenLimit = Global.getOffscreenLimit();
        for (int i = newPage - offScreenLimit; i <= newPage + offScreenLimit; i++) {
            fragment = getActualFragment(i);
            if (fragment == null) continue;
            if (i == newPage) fragment.loadImage(Priority.IMMEDIATE);
            else fragment.loadImage();
        }
    }

    private void clearFarRequests(int oldPage, int newPage) {
        ZoomFragment fragment;
        int offScreenLimit = Global.getOffscreenLimit();
        for (int i = oldPage - offScreenLimit; i <= oldPage + offScreenLimit; i++) {
            if (i >= newPage - offScreenLimit && i <= newPage + offScreenLimit) continue;
            fragment = getActualFragment(i);
            if (fragment == null) continue;
            fragment.cancelRequest();
        }

    }

    private ZoomFragment getActualFragment(int position) {
        if (doublePageMode && !continuousMode) {
            Fragment fragment = getSupportFragmentManager().findFragmentByTag("f" + (position / 2));
            return fragment instanceof DoublePageFragment
                ? ((DoublePageFragment) fragment).pageFragment(position) : null;
        }
        return (ZoomFragment) getSupportFragmentManager().findFragmentByTag("f" + position);
    }

    private void sendImage(boolean withText) {
        Drawable image = getAvailablePageImage();
        if (image == null) {
            showPageUnavailable();
            return;
        }
        int pageNum = readerNavigation.currentPage();
        Utility.sendImage(this, image, withText ? gallery.sharePageUrl(pageNum) : null);
    }

    private void downloadPage() {
        Drawable image = getAvailablePageImage();
        if (image == null) {
            showPageUnavailable();
            return;
        }
        final File output = new File(Global.SCREENFOLDER, gallery.getId() + "-" + (readerNavigation.currentPage() + 1) + ".jpg");
        Utility.saveImage(image, output);
    }

    private Drawable getAvailablePageImage() {
        if (continuousMode) return null;
        ZoomFragment fragment = getActualFragment();
        Drawable image = fragment == null ? null : fragment.getDrawable();
        return image instanceof BitmapDrawable ? image : null;
    }

    @Nullable
    private Object continuousPageSource(int page) {
        RecyclerView.ViewHolder holder = continuousReader.findViewHolderForAdapterPosition(page);
        RecyclerView.Adapter<?> adapter = continuousReader.getAdapter();
        if (holder == null || !(adapter instanceof ContinuousPageAdapter) || DebugImageMask.isEnabled(this)) return null;
        return ((ContinuousPageAdapter) adapter).loadedSource(holder);
    }

    private void exportContinuousPage(int page, @NonNull Object source, boolean share, boolean withText) {
        boolean local = source instanceof File || (source instanceof Uri && "file".equals(((Uri) source).getScheme()));
        if (DebugImageMask.isEnabled(this) || (!local
            && !source.equals(ContinuousPageAdapter.pageSource(gallery, directory, page)))) {
            showPageUnavailable();
            return;
        }
        if (pageExportTask != null && !pageExportTask.isDone()) {
            Toast.makeText(this, R.string.reader_page_loading, Toast.LENGTH_SHORT).show();
            return;
        }
        Context app = getApplicationContext();
        File destination = share ? new File(app.getCacheDir(), "reader-share") : Global.SCREENFOLDER;
        if (destination == null) {
            Toast.makeText(this, R.string.download_error_storage, Toast.LENGTH_SHORT).show();
            return;
        }
        String name = gallery.getId() + "-" + (page + 1);
        boolean unique = share || gallery.getId() <= 0;
        String caption = share && withText ? gallery.sharePageUrl(page) : null;
        WeakReference<ZoomActivity> activity = new WeakReference<>(this);
        Handler main = new Handler(Looper.getMainLooper());
        Toast.makeText(this, R.string.reader_page_loading, Toast.LENGTH_SHORT).show();
        pageExportTask = PAGE_EXPORT_EXECUTOR.submit(() -> {
            try {
                ReaderPageExport.Result result = ReaderPageExport.prepare(app, source, destination, name, unique);
                if (Thread.currentThread().isInterrupted()) return;
                main.post(() -> {
                    ZoomActivity current = activity.get();
                    if (current == null || current.isFinishing() || current.isDestroyed()) return;
                    if (share) current.shareContinuousPage(result, caption);
                    else Toast.makeText(current, R.string.export_finished, Toast.LENGTH_SHORT).show();
                });
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } catch (IOException | RuntimeException exception) {
                LogUtility.e("Reader page export failed: " + exception.getClass().getSimpleName());
                main.post(() -> {
                    ZoomActivity current = activity.get();
                    if (current != null && !current.isFinishing() && !current.isDestroyed())
                        Toast.makeText(current, share ? R.string.send_image_error : R.string.download_failed, Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private void shareContinuousPage(ReaderPageExport.Result result, @Nullable String caption) {
        try {
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".provider", result.file);
            Intent intent = new Intent(Intent.ACTION_SEND).setType(result.mimeType)
                .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            if (caption != null) intent.putExtra(Intent.EXTRA_TEXT, caption);
            startActivity(Intent.createChooser(intent, getString(R.string.share_with)));
        } catch (IllegalArgumentException | ActivityNotFoundException exception) {
            LogUtility.e("Reader share failed: " + exception.getClass().getSimpleName());
            Toast.makeText(this, R.string.send_image_error, Toast.LENGTH_SHORT).show();
        }
    }

    private void showPageUnavailable() {
        Toast.makeText(this, R.string.reader_page_unavailable, Toast.LENGTH_SHORT).show();
    }

    private void animateLayout() {

        AnimatorListenerAdapter adapter = new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (isHidden) {
                    pageSwitcher.setVisibility(View.GONE);
                    toolbar.setVisibility(View.GONE);
                    view.setVisibility(View.GONE);
                    cornerPageViewer.setVisibility(View.VISIBLE);
                }
            }
        };

        pageSwitcher.setVisibility(View.VISIBLE);
        toolbar.setVisibility(View.VISIBLE);
        view.setVisibility(View.VISIBLE);
        cornerPageViewer.setVisibility(View.GONE);

        pageSwitcher.animate().alpha(isHidden ? 0f : 0.75f).setDuration(150).setListener(adapter).start();
        view.animate().alpha(isHidden ? 0f : 0.75f).setDuration(150).setListener(adapter).start();
        toolbar.animate().alpha(isHidden ? 0f : 0.75f).setDuration(150).setListener(adapter).start();
    }

    private void applyVisibilityFlag() {
        getWindow().getDecorView().setSystemUiVisibility(isHidden ? hideFlags : showFlags);
    }

    public class DoublePagePagerAdapter extends FragmentStateAdapter {
        public DoublePagePagerAdapter(ZoomActivity activity) {
            super(activity.getSupportFragmentManager(), activity.getLifecycle());
        }

        @NonNull
        @Override
        public Fragment createFragment(int position) {
            int firstPage = SpreadReaderNavigation.firstPageForSpread(position, gallery.getPageCount());
            int secondPage = SpreadReaderNavigation.secondPageForSpread(position, gallery.getPageCount());
            return DoublePageFragment.newInstance(gallery, firstPage, secondPage, directory, readerScaleMode);
        }

        @Override
        public int getItemCount() {
            return SpreadReaderNavigation.spreadCount(gallery.getPageCount());
        }
    }

    public class SectionsPagerAdapter extends FragmentStateAdapter {
        public SectionsPagerAdapter(ZoomActivity activity) {
            super(activity.getSupportFragmentManager(), activity.getLifecycle());

        }

        @NonNull
        @Override
        public Fragment createFragment(int position) {
            return ZoomFragment.newInstance(gallery, position, directory, readerScaleMode);
        }

        @Override
        public int getItemCount() {
            return gallery.getPageCount();
        }
    }
}
