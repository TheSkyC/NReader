// Modified for NReader by TheSkyC (2026); see NOTICE.
package com.maxwai.nclientv3;

import android.Manifest;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.widget.SeekBar;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.widget.Toolbar;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.GridLayoutManager;
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
import com.maxwai.nclientv3.components.views.ZoomFragment;
import com.maxwai.nclientv3.files.GalleryFolder;
import com.maxwai.nclientv3.settings.DefaultDialogs;
import com.maxwai.nclientv3.settings.Global;
import com.maxwai.nclientv3.utility.LogUtility;
import com.maxwai.nclientv3.utility.Utility;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.radiobutton.MaterialRadioButton;
import com.google.android.material.switchmaterial.SwitchMaterial;

import java.io.File;
import java.util.Objects;

public class ZoomActivity extends GeneralActivity {
    private final static int hideFlags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
        | View.SYSTEM_UI_FLAG_FULLSCREEN
        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
    private final static int showFlags = View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_STABLE;
    private static final String VOLUME_SIDE_KEY = "volumeSide";
    private static final String SCROLL_TYPE_KEY = "zoomScrollType";
    private static final String INSTANT_PAGE_TURN_KEY = "instant_page_turn";
    private static final String READER_OFFSET_STATE = "readerOffset";
    private static final float PAGE_TAP_EDGE = .2f;
    private GenericGallery gallery;
    private int actualPage = 0;
    private ReadingPosition initialPosition;
    private boolean initializingPager = true;
    private boolean isHidden = false;
    private ViewPager2 mViewPager;
    private ReaderNavigation readerNavigation;
    private TextView pageManagerLabel, cornerPageViewer;
    private View pageSwitcher;
    private SeekBar seekBar;
    private Toolbar toolbar;
    private View view;
    private GalleryFolder directory;
    @ViewPager2.Orientation
    private int tmpScrollType;
    private boolean up = false, down = false, side;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
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
        SectionsPagerAdapter mSectionsPagerAdapter = new SectionsPagerAdapter(this);
        mViewPager = findViewById(R.id.container);
        mViewPager.setAdapter(mSectionsPagerAdapter);
        readerNavigation = new PagedReaderNavigation(mViewPager);
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

        //initial setup for views
        changeLayout(getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE);
        mViewPager.setKeepScreenOn(Global.isLockScreen());
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
                int oldPage = actualPage;
                actualPage = newPage;
                if (!initializingPager && initialPosition.page != newPage + 1) {
                    initialPosition = new ReadingPosition(newPage + 1, 0f);
                }
                LogUtility.d("Page selected: " + newPage + " from page " + oldPage);
                setPageText(newPage + 1);
                seekBar.setProgress(newPage);
                clearFarRequests(oldPage, newPage);
                makeNearRequests(newPage);
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
    }

    public void onReaderPageTap(ZoomFragment source, float horizontalPosition) {
        if (source != getActualFragment()) return;
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
            }
        }
    }

    private void savePosition() {
        if (gallery != null && readerNavigation != null && initialPosition != null) {
            int page = readerNavigation.currentPage() + 1;
            ReadingProgress.save(gallery.getId(), initialPosition.page == page
                ? initialPosition : new ReadingPosition(page, 0f));
        }
    }

    @Override
    protected void onPause() {
        savePosition();
        super.onPause();
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle state) {
        if (readerNavigation != null) {
            int page = readerNavigation.currentPage() + 1;
            state.putInt("readerPage", page);
            state.putFloat(READER_OFFSET_STATE, initialPosition != null && initialPosition.page == page
                ? initialPosition.offset : 0f);
        }
        savePosition();
        super.onSaveInstanceState(state);
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
        if (Global.useRtl()) next = !next;
        int current = readerNavigation.currentPage();
        if (next && current < readerNavigation.pageCount() - 1) changePage(current + 1);
        if (!next && current > 0) changePage(current - 1);
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        changeLayout(newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE);
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
            if (tmpScrollType != scrollType) {
                setReaderOrientation(tmpScrollType);
            }
        }).setNegativeButton(R.string.cancel, null);
        builder.show();
    }

    private void setReaderOrientation(@ViewPager2.Orientation int orientation) {
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
        direction.check(isVerticalPageTurn() ? R.id.reader_vertical : R.id.reader_horizontal);
        direction.setOnCheckedChangeListener((group, id) -> {
            if (id == R.id.reader_horizontal) setReaderOrientation(ViewPager2.ORIENTATION_HORIZONTAL);
            else if (id == R.id.reader_vertical) setReaderOrientation(ViewPager2.ORIENTATION_VERTICAL);
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

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_zoom, menu);
        Utility.tintMenu(this, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();
        if (id == R.id.rotate) {
            getActualFragment().rotate();
        } else if (id == R.id.save_page) {
            if (Global.hasStoragePermission(this)) {
                downloadPage();
            } else requestStorage();
        } else if (id == R.id.share) {
            if (gallery.getId() <= 0) sendImage(false);
            else openSendImageDialog();
        } else if (id == android.R.id.home) {
            finish();
            return true;
        } else if (id == R.id.bookmark) {
            Queries.ResumeTable.insert(gallery.getId(), readerNavigation.currentPage() + 1);
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

    private void requestStorage() {
        requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE}, 1);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        Global.initStorage(this);
        if (requestCode == 1 && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED)
            downloadPage();

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
        return (ZoomFragment) getSupportFragmentManager().findFragmentByTag("f" + position);
    }

    private void sendImage(boolean withText) {
        int pageNum = readerNavigation.currentPage();
        Utility.sendImage(this, getActualFragment().getDrawable(), withText ? gallery.sharePageUrl(pageNum) : null);
    }

    private void downloadPage() {
        final File output = new File(Global.SCREENFOLDER, gallery.getId() + "-" + (readerNavigation.currentPage() + 1) + ".jpg");
        Utility.saveImage(getActualFragment().getDrawable(), output);
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

    public class SectionsPagerAdapter extends FragmentStateAdapter {
        public SectionsPagerAdapter(ZoomActivity activity) {
            super(activity.getSupportFragmentManager(), activity.getLifecycle());

        }

        @NonNull
        @Override
        public Fragment createFragment(int position) {
            return ZoomFragment.newInstance(gallery, position, directory);
        }

        @Override
        public int getItemCount() {
            return gallery.getPageCount();
        }
    }
}
