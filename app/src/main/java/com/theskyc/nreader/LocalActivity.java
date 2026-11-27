// Modified for NReader by TheSkyC (2026); see NOTICE.
package com.theskyc.nreader;

import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.ActionBar;

import com.theskyc.nreader.adapters.LocalAdapter;
import com.theskyc.nreader.api.local.FakeInspector;
import com.theskyc.nreader.api.local.LocalGallery;
import com.theskyc.nreader.api.local.LocalSortType;
import com.theskyc.nreader.async.converters.CreatePdfOrZip;
import com.theskyc.nreader.async.downloader.GalleryDownloaderV2;
import com.theskyc.nreader.components.activities.BaseActivity;
import com.theskyc.nreader.components.classes.MultichoiceAdapter;
import com.theskyc.nreader.components.widgets.GalleryListGrid;
import com.theskyc.nreader.components.widgets.LiveSearchResults;
import com.theskyc.nreader.components.widgets.ResultsSearchView;
import com.theskyc.nreader.settings.Global;
import com.theskyc.nreader.settings.MainGridSettings;
import com.theskyc.nreader.utility.Utility;
import com.google.android.material.appbar.AppBarLayout;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.search.SearchBar;
import com.google.android.material.search.SearchView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;

import java.io.File;
import java.util.List;
import java.util.Objects;

public class LocalActivity extends BaseActivity {
    private static final String STATE_SEARCH_QUERY = "local_search_query";
    private static final String STATE_SEARCH_EXPANDED = "local_search_expanded";

    @Override protected int getBlackThemeStyle() {
        return R.style.AppTheme_Material3_Black;
    }

    private Menu optionMenu;
    private LocalAdapter adapter;
    private final MultichoiceAdapter.MultichoiceListener listener = new MultichoiceAdapter.DefaultMultichoiceListener() {

        @Override
        public void choiceChanged() {
            setMenuVisibility(optionMenu);
        }

        @Override
        public void firstChoice() {
            if (searchView.isShowing()) searchView.hide();
            ((AppBarLayout) findViewById(R.id.local_app_bar)).setExpanded(true, true);
        }
    };
    private SearchBar searchBar;
    private SearchView searchView;
    private GalleryListGrid listGrid;
    private int idGalleryPosition = -1;
    private File folder = Global.MAINFOLDER;
    private String query = "";
    private boolean restoreSearchExpanded;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState != null) {
            query = savedInstanceState.getString(STATE_SEARCH_QUERY, "");
            restoreSearchExpanded = savedInstanceState.getBoolean(STATE_SEARCH_EXPANDED, false);
        }
        //Global.initActivity(this);
        setContentView(R.layout.activity_local);
        searchBar = findViewById(R.id.toolbar);
        setSupportActionBar(searchBar);
        ActionBar actionBar = Objects.requireNonNull(getSupportActionBar());
        actionBar.setDisplayHomeAsUpEnabled(true);
        actionBar.setDisplayShowTitleEnabled(false);
        searchView = findViewById(R.id.search_view);
        searchView.setupWithSearchBar(searchBar);
        LiveSearchResults.attach((ResultsSearchView) searchView, findViewById(R.id.master_layout));
        searchView.getEditText().setHint(R.string.search);
        searchView.getEditText().addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                applySearchQuery(s.toString());
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });
        setSearchQuery(query);
        if (restoreSearchExpanded) {
            searchView.show();
            restoreSearchExpanded = false;
        }
        findViewById(R.id.page_switcher).setVisibility(View.GONE);
        recycler = findViewById(R.id.recycler);
        refresher = findViewById(R.id.refresher);
        refresher.setOnRefreshListener(() -> new FakeInspector(this, folder).execute(this));
        changeLayout(getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE);
        new FakeInspector(this, folder).execute(this);
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (searchView.isShowing())
                    searchView.hide();
                else if (adapter != null && adapter.getMode() == MultichoiceAdapter.Mode.SELECTING)
                    adapter.deselectAll();
                else {
                    setEnabled(false);
                    getOnBackPressedDispatcher().onBackPressed();
                    setEnabled(true);
                }
            }
        });
    }

    public void setAdapter(LocalAdapter adapter) {
        this.adapter = adapter;
        this.adapter.addListener(listener);
        runOnUiThread(() -> {
            recycler.setAdapter(adapter);
            adapter.filterQuery(query);
            listGrid.refresh();
        });
    }

    public void setIdGalleryPosition(int idGalleryPosition) {
        this.idGalleryPosition = idGalleryPosition;
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.download, menu);
        getMenuInflater().inflate(R.menu.local_multichoice, menu);
        this.optionMenu = menu;
        listGrid.bindMenu(menu.findItem(R.id.list_columns));
        setMenuVisibility(menu);
        Utility.tintMenu(this, menu);

        return true;
    }

    private void setMenuVisibility(Menu menu) {
        if (menu == null) return;
        MultichoiceAdapter.Mode mode = adapter == null ? MultichoiceAdapter.Mode.NORMAL : adapter.getMode();
        boolean normalMode = mode == MultichoiceAdapter.Mode.NORMAL;
        searchBar.setEnabled(normalMode);
        if (!normalMode && searchView.isShowing()) searchView.hide();
        boolean hasGallery = false;
        boolean hasDownloads = false;
        if (mode == MultichoiceAdapter.Mode.SELECTING) {
            hasGallery = adapter.hasSelectedClass(LocalGallery.class);
            hasDownloads = adapter.hasSelectedClass(GalleryDownloaderV2.class);
        }

        menu.findItem(R.id.list_columns).setVisible(normalMode);
        menu.findItem(R.id.sort_by_name).setVisible(normalMode);
        menu.findItem(R.id.filter_download_state).setVisible(normalMode);
        menu.findItem(R.id.filter_personal_state).setVisible(normalMode);
        menu.findItem(R.id.folder_choose).setVisible(normalMode && Global.getUsableFolders(this).size() > 1);
        menu.findItem(R.id.random_favorite).setVisible(normalMode);

        menu.findItem(R.id.delete_all).setVisible(mode == MultichoiceAdapter.Mode.SELECTING);
        menu.findItem(R.id.select_all).setVisible(mode == MultichoiceAdapter.Mode.SELECTING);
        menu.findItem(R.id.pause_all).setVisible(mode == MultichoiceAdapter.Mode.SELECTING && !hasGallery && hasDownloads);
        menu.findItem(R.id.start_all).setVisible(mode == MultichoiceAdapter.Mode.SELECTING && !hasGallery && hasDownloads);
        menu.findItem(R.id.pdf_all).setVisible(mode == MultichoiceAdapter.Mode.SELECTING && hasGallery && !hasDownloads && CreatePdfOrZip.hasPDFCapabilities());
        menu.findItem(R.id.zip_all).setVisible(mode == MultichoiceAdapter.Mode.SELECTING && hasGallery && !hasDownloads);
    }

    @Override
    protected void onDestroy() {
        if (listGrid != null) listGrid.close();
        if (adapter != null) adapter.removeObserver();
        super.onDestroy();
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putString(STATE_SEARCH_QUERY, query);
        state.putBoolean(STATE_SEARCH_EXPANDED, searchView != null && searchView.isShowing());
    }

    @Override
    protected void changeLayout(boolean landscape) {
        if (listGrid == null) listGrid = new GalleryListGrid(recycler, MainGridSettings.forDownloads(this));
        else listGrid.refresh();
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_DOWN && searchView != null
            && searchView.getEditText().hasFocus()) {
            Rect bounds = new Rect();
            if (searchView.getEditText().getGlobalVisibleRect(bounds)
                && !bounds.contains((int) event.getRawX(), (int) event.getRawY())) searchView.clearFocus();
        }
        return super.dispatchTouchEvent(event);
    }

    @Override
    protected void onPause() {
        if (searchView != null) searchView.getEditText().clearFocus();
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (listGrid != null) listGrid.refresh();
        if (idGalleryPosition != -1 && adapter != null) {
            adapter.updateColor(idGalleryPosition);
            idGalleryPosition = -1;
        }
    }

    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            getOnBackPressedDispatcher().onBackPressed();
            return true;
        } else if (item.getItemId() == R.id.list_columns) {
            listGrid.cycle();
            return true;
        } else if (item.getItemId() == R.id.pause_all) {
            adapter.pauseSelected();
        } else if (item.getItemId() == R.id.start_all) {
            adapter.startSelected();
        } else if (item.getItemId() == R.id.delete_all) {
            adapter.deleteSelected();
        } else if (item.getItemId() == R.id.pdf_all) {
            adapter.pdfSelected();
        } else if (item.getItemId() == R.id.zip_all) {
            adapter.zipSelected();
        } else if (item.getItemId() == R.id.select_all) {
            adapter.selectAll();
        } else if (item.getItemId() == R.id.folder_choose) {
            showDialogFolderChoose();
        } else if (item.getItemId() == R.id.random_favorite) {
            if (adapter != null) adapter.viewRandom();
        } else if (item.getItemId() == R.id.sort_by_name) {
            dialogSortType();
        } else if (item.getItemId() == R.id.filter_download_state) {
            dialogDownloadFilter();
        } else if (item.getItemId() == R.id.filter_personal_state) {
            dialogPersonalFilter();
        }
        return super.onOptionsItemSelected(item);
    }

    private void dialogDownloadFilter() {
        if (adapter == null) return;
        LocalAdapter.DownloadFilter[] values = LocalAdapter.DownloadFilter.values();
        String[] labels = {
            getString(R.string.filter_all), getString(R.string.filter_complete),
            getString(R.string.filter_partial), getString(R.string.filter_damaged),
            getString(R.string.filter_unverified), getString(R.string.filter_absent)
        };
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this)
            .setTitle(R.string.filter_download_state)
            .setSingleChoiceItems(labels, adapter.getDownloadFilter().ordinal(), (dialog, which) -> {
                adapter.setDownloadFilter(values[which]);
                dialog.dismiss();
            })
            .setNegativeButton(R.string.cancel, null);
        builder.show();
    }

    private void dialogPersonalFilter() {
        if (adapter == null) return;
        String[] labels = {
            getString(R.string.filter_favorite_only), getString(R.string.filter_reading_only),
            getString(R.string.filter_unread_only), getString(R.string.filter_custom_status_only)
        };
        boolean[] checked = {
            adapter.isFavoriteOnly(), adapter.getReadingFilter() == 1,
            adapter.getReadingFilter() == 2, adapter.isCustomStatusOnly()
        };
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this)
            .setTitle(R.string.filter_personal_state)
            .setMultiChoiceItems(labels, checked, (dialog, which, isChecked) -> checked[which] = isChecked)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.ok, (dialog, which) -> {
                int reading = checked[1] ? 1 : checked[2] ? 2 : 0;
                adapter.setPersonalFilters(checked[0], reading, checked[3]);
            });
        builder.show();
    }

    private void showDialogFolderChoose() {
        List<File> folders = Global.getUsableFolders(this);
        folders.removeIf(Objects::isNull);
        String[] labels = new String[folders.size()];
        for (int index = 0; index < folders.size(); index++)
            labels[index] = folders.get(index).toString();
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this);
        builder.setTitle(R.string.choose_directory).setIcon(R.drawable.ic_folder);
        builder.setItems(labels, (dialog, which) -> {
            folder = new File(folders.get(which), Global.MAINFOLDER_NAME);
            new FakeInspector(this, folder).execute(this);
        }).setNegativeButton(R.string.cancel, null).show();
    }

    private void dialogSortType() {
        LocalSortType sortType = Global.getLocalSortType();
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this);
        View view = LayoutInflater.from(this).inflate(R.layout.local_sort_type, searchBar, false);
        ChipGroup group = view.findViewById(R.id.chip_group);
        MaterialSwitch switchMaterial = view.findViewById(R.id.ascending);
        MaterialSwitch pinDownloads = view.findViewById(R.id.pin_downloads);
        group.check(group.getChildAt(sortType.type.ordinal()).getId());
        switchMaterial.setChecked(sortType.descending);
        pinDownloads.setChecked(Global.isLocalPinDownloads());
        builder.setView(view);
        builder.setPositiveButton(R.string.ok, (dialog, which) -> {
            int typeSelectedIndex = group.indexOfChild(group.findViewById(group.getCheckedChipId()));
            LocalSortType.Type typeSelected = LocalSortType.Type.values()[typeSelectedIndex];
            boolean descending = switchMaterial.isChecked();
            LocalSortType newSortType = new LocalSortType(typeSelected, descending);
            boolean pin = pinDownloads.isChecked();
            if (sortType.equals(newSortType) && pin == Global.isLocalPinDownloads()) return;
            Global.setLocalSortType(LocalActivity.this, newSortType);
            Global.setLocalPinDownloads(LocalActivity.this, pin);
            if (adapter != null) adapter.sortChanged();
        })
            .setNeutralButton(R.string.cancel, null)
            .setTitle(R.string.sort_select_type)
            .show();


       /* boolean sortByName=Global.isLocalSortByName();
        item.setIcon(sortByName?R.drawable.ic_sort_by_alpha:R.drawable.ic_access_time);
        item.setTitle(sortByName?R.string.sort_by_title:R.string.sort_by_latest);
        Global.setTint(item.getIcon());*/
    }

    @Override
    protected int getPortraitColumnCount() {
        return Global.getColPortDownload();
    }

    @Override
    protected int getLandscapeColumnCount() {
        return Global.getColLandDownload();
    }

    public String getQuery() {
        return query;
    }

    private void setSearchQuery(String value) {
        String newQuery = value == null ? "" : value;
        searchBar.setText(newQuery);
        if (newQuery.equals(searchView.getEditText().getText().toString())) {
            applySearchQuery(newQuery);
        } else {
            searchView.setText(newQuery);
        }
    }

    private void applySearchQuery(String value) {
        query = value;
        searchBar.setText(query);
        if (adapter != null) adapter.filterQuery(query);
    }
}
