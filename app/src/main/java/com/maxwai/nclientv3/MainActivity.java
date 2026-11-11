// Modified for NReader by TheSkyC (2026); see NOTICE.
package com.maxwai.nclientv3;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.ActionBarDrawerToggle;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.appcompat.widget.SearchView;
import androidx.appcompat.widget.Toolbar;
import androidx.core.os.LocaleListCompat;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.navigation.NavigationView;
import com.google.android.material.snackbar.Snackbar;
import com.maxwai.nclientv3.adapters.ListAdapter;
import com.maxwai.nclientv3.api.InspectorV3;
import com.maxwai.nclientv3.api.components.Gallery;
import com.maxwai.nclientv3.api.components.GenericGallery;
import com.maxwai.nclientv3.api.components.Ranges;
import com.maxwai.nclientv3.api.components.Tag;
import com.maxwai.nclientv3.api.enums.ApiRequestType;
import com.maxwai.nclientv3.api.enums.Language;
import com.maxwai.nclientv3.api.enums.SortType;
import com.maxwai.nclientv3.api.enums.SpecialTagIds;
import com.maxwai.nclientv3.api.enums.TagStatus;
import com.maxwai.nclientv3.api.enums.TagType;
import com.maxwai.nclientv3.async.ScrapeTags;
import com.maxwai.nclientv3.async.VersionChecker;
import com.maxwai.nclientv3.async.database.Queries;
import com.maxwai.nclientv3.async.database.SearchPlanStore;
import com.maxwai.nclientv3.async.downloader.DownloadGalleryV2;
import com.maxwai.nclientv3.components.activities.BaseActivity;
import com.maxwai.nclientv3.components.views.PageSwitcher;
import com.maxwai.nclientv3.components.widgets.CustomGridLayoutManager;
import com.maxwai.nclientv3.components.widgets.GalleryListGrid;
import com.maxwai.nclientv3.settings.AuthStore;
import com.maxwai.nclientv3.settings.Global;
import com.maxwai.nclientv3.settings.Login;
import com.maxwai.nclientv3.settings.TagV2;
import com.maxwai.nclientv3.utility.LogUtility;
import com.maxwai.nclientv3.utility.Utility;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends BaseActivity
    implements NavigationView.OnNavigationItemSelectedListener {

    private static final int CHANGE_LANGUAGE_DELAY = 1000;
    private static boolean firstTime = true;//true only when app starting
    private final InspectorV3.InspectorResponse startGallery = new MainInspectorResponse() {
        @Override
        public void onSuccess(List<GenericGallery> galleries) {
            Gallery g = galleries.size() == 1 ? (Gallery) galleries.get(0) : Gallery.emptyGallery();
            Intent intent = new Intent(MainActivity.this, GalleryActivity.class);
            LogUtility.d(g.toString());
            intent.putExtra(getPackageName() + ".GALLERY", g);
            runOnUiThread(() -> {
                startActivity(intent);
                finish();
            });
            LogUtility.d("STARTED");
        }
    };
    private final Handler changeLanguageTimeHandler = new Handler(Objects.requireNonNull(Looper.myLooper()));
    public ListAdapter adapter;
    private final InspectorV3.InspectorResponse addDataset = new MainInspectorResponse() {
        @Override
        public void onSuccess(List<GenericGallery> galleries) {
            adapter.addGalleries(galleries);
        }
    };
    //views
    public MenuItem onlineFavoriteManager;
    private InspectorV3 inspector = null;
    private NavigationView navigationView;
    private ModeType modeType = ModeType.UNKNOWN;
    private int idOpenedGallery = -1;//Position in the recycler of the opened gallery
    private boolean inspecting = false, filteringTag = false;
    private SortType temporaryType;
    private Snackbar snackbar = null;
    private PageSwitcher pageSwitcher;
    private final InspectorV3.InspectorResponse
        resetDataset = new MainInspectorResponse() {
        @Override
        public void onSuccess(List<GenericGallery> galleries) {
            super.onSuccess(galleries);
            adapter.restartDataset(galleries);
            showPageSwitcher(inspector.getPage(), inspector.getPageCount());
            runOnUiThread(() -> recycler.smoothScrollToPosition(0));
        }
    };
    final Runnable changeLanguageRunnable = () -> {
        useNormalMode();
        inspector.start();
    };
    private DrawerLayout drawerLayout;
    private Toolbar toolbar;
    private boolean setting = false;
    private GalleryListGrid listGrid;
    private List<Uri> pendingSharedLinks;
    private static final Pattern SHARED_GALLERY_LINK = Pattern.compile(
        "https?://nhentai\\.net/g/\\d+(?:/\\d+)?/?", Pattern.CASE_INSENSITIVE);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        //load inspector
        selectStartMode(getIntent(), getPackageName());
        LogUtility.d("Main started with mode " + modeType);
        //init views and actions
        findUsefulViews();
        initializeToolbar();
        initializeNavigationView();
        initializeRecyclerView();
        initializePageSwitcherActions();
        refresher.setOnRefreshListener(() -> {
            inspector = inspector.cloneInspector(MainActivity.this, resetDataset);
            if (Global.isInfiniteScrollMain()) inspector.setPage(1);
            inspector.start();
        });

        manageDrawer();
        setActivityTitle();
        if (pendingSharedLinks != null) {
            List<Uri> links = pendingSharedLinks;
            pendingSharedLinks = null;
            String[] labels = new String[links.size()];
            for (int i = 0; i < links.size(); i++) labels[i] = links.get(i).toString();
            new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.title_activity_gallery)
                .setItems(labels, (dialog, which) -> {
                    Intent selected = new Intent(this, MainActivity.class).setData(links.get(which));
                    startActivity(selected);
                    finish();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
        }
        if (firstTime) checkUpdate();
        if (inspector != null) {
            inspector.start();
        } else {
            LogUtility.e(getIntent().getExtras());
        }

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (drawerLayout.isDrawerOpen(GravityCompat.START))
                    drawerLayout.closeDrawer(GravityCompat.START);
                else {
                    setEnabled(false);
                    getOnBackPressedDispatcher().onBackPressed();
                    setEnabled(true);
                }
            }
        });
    }

    private void manageDrawer() {
        if (modeType != ModeType.NORMAL) {
            drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED);
        } else {
            ActionBarDrawerToggle toggle = new ActionBarDrawerToggle(this, drawerLayout, toolbar, R.string.navigation_drawer_open, R.string.navigation_drawer_close);
            drawerLayout.addDrawerListener(toggle);
            toggle.syncState();
        }
    }

    private void setActivityTitle() {
        ActionBar actionBar = Objects.requireNonNull(getSupportActionBar());
        switch (modeType) {
            case FAVORITE:
                actionBar.setTitle(R.string.favorite_online_manga);
                break;
            case SEARCH:
                actionBar.setTitle(inspector.getSearchTitle());
                break;
            case TAG:
                actionBar.setTitle(inspector.getTag().getName());
                break;
            case NORMAL:
                actionBar.setTitle(R.string.app_name);
                break;
            default:
                actionBar.setTitle(R.string.app_name);
                break;
        }
    }

    private void initializeToolbar() {
        setSupportActionBar(toolbar);
        ActionBar bar = getSupportActionBar();
        assert bar != null;
        bar.setDisplayShowTitleEnabled(true);
        bar.setTitle(R.string.app_name);
    }

    private void initializePageSwitcherActions() {
        pageSwitcher.setChanger(new PageSwitcher.DefaultPageChanger() {
            @Override
            public void pageChanged() {
                inspector = inspector.cloneInspector(MainActivity.this, resetDataset);
                inspector.setPage(pageSwitcher.getActualPage());
                inspector.start();
            }
        });
    }

    private void initializeRecyclerView() {
        adapter = new ListAdapter(this);
        recycler.setAdapter(adapter);
        //recycler.setItemViewCacheSize(24);
        recycler.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                if (inspecting) return;
                if (!Global.isInfiniteScrollMain()) return;
                if (refresher.isRefreshing()) return;

                CustomGridLayoutManager manager = (CustomGridLayoutManager) recycler.getLayoutManager();
                assert manager != null;
                if (!pageSwitcher.lastPageReached() && lastGalleryReached(manager)) {
                    inspecting = true;
                    inspector = inspector.cloneInspector(MainActivity.this, addDataset);
                    inspector.setPage(inspector.getPage() + 1);
                    inspector.start();
                }
            }
        });
        changeLayout(getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE);
    }

    /**
     * Check if the last gallery has been shown
     **/
    private boolean lastGalleryReached(CustomGridLayoutManager manager) {
        return recycler.getAdapter() != null && manager.findLastVisibleItemPosition() >= (recycler.getAdapter().getItemCount() - 1 - manager.getSpanCount());
    }

    @Override
    protected void changeLayout(boolean landscape) {
        if (listGrid == null) listGrid = new GalleryListGrid(recycler);
        else listGrid.refresh();
    }

    @Override
    protected void onDestroy() {
        if (listGrid != null) listGrid.close();
        if (adapter != null) adapter.close();
        super.onDestroy();
    }

    private void initializeNavigationView() {
        toolbar.setNavigationIcon(R.drawable.ic_arrow_back);
        toolbar.setNavigationOnClickListener(v -> finish());
        navigationView.setNavigationItemSelectedListener(this);
        onlineFavoriteManager.setVisible(AuthStore.hasOnlineIdentity(this));
    }

    public void setIdOpenedGallery(int idOpenedGallery) {
        this.idOpenedGallery = idOpenedGallery;
    }

    private void findUsefulViews() {
        masterLayout = findViewById(R.id.master_layout);
        toolbar = findViewById(R.id.toolbar);
        navigationView = findViewById(R.id.nav_view);
        recycler = findViewById(R.id.recycler);
        refresher = findViewById(R.id.refresher);
        pageSwitcher = findViewById(R.id.page_switcher);
        drawerLayout = findViewById(R.id.drawer_layout);
        onlineFavoriteManager = navigationView.getMenu().findItem(R.id.online_favorite_manager);
    }

    private void hideError() {
        //errorText.setVisibility(View.GONE);
        runOnUiThread(() -> {
            if (snackbar != null && snackbar.isShown()) {
                snackbar.dismiss();
                snackbar = null;
            }
        });
    }

    private void showError(@Nullable String text, @Nullable View.OnClickListener listener) {
        if (text == null) {
            hideError();
            return;
        }
        if (listener == null) {
            snackbar = Snackbar.make(masterLayout, text, Snackbar.LENGTH_SHORT);
        } else {
            snackbar = Snackbar.make(masterLayout, text, Snackbar.LENGTH_INDEFINITE);
            snackbar.setAction(R.string.retry, listener);
        }
        snackbar.show();
    }

    private void showError(@StringRes int text, View.OnClickListener listener) {
        showError(getString(text), listener);
    }

    private void checkUpdate() {
        if (Global.shouldCheckForUpdates(this))
            new VersionChecker(this, true);
        ScrapeTags.startWork(this);
        firstTime = false;
    }

    private void selectStartMode(Intent intent, String packageName) {
        try {
            Uri data = intent.getData();
            if (Intent.ACTION_SEND.equals(intent.getAction()))
                useSharedTextMode(intent);
            else if (intent.getBooleanExtra(packageName + ".ISBYTAG", false))
                useTagMode(intent, packageName);
            else if (intent.getBooleanExtra(packageName + ".SEARCHMODE", false))
                useSearchMode(intent, packageName);
            else if (intent.getBooleanExtra(packageName + ".FAVORITE", false)) useFavoriteMode(1);
            else if (intent.getBooleanExtra(packageName + ".BYBOOKMARK", false))
                useBookmarkMode(intent, packageName);
            else if (data != null) manageDataStart(data);
            else useNormalMode();
        } catch (RuntimeException exception) {
            LogUtility.w("Ignoring invalid launch intent", exception);
            useNormalMode();
        }
    }

    private void useNormalMode() {
        inspector = InspectorV3.basicInspector(this, 1, resetDataset);
        modeType = ModeType.NORMAL;
    }

    private void useSharedTextMode(Intent intent) {
        String text = intent.getStringExtra(Intent.EXTRA_TEXT);
        List<Uri> links = extractSharedGalleryLinks(text);
        if (links.size() == 1) {
            manageDataStart(links.get(0));
            return;
        }
        if (links.size() > 1) {
            pendingSharedLinks = links;
            useNormalMode();
            return;
        }
        if (text == null || text.trim().isEmpty()) {
            useNormalMode();
            return;
        }
        inspector = InspectorV3.searchInspector(this, text.trim(), null, 1,
            Global.getSortType(), null, resetDataset);
        modeType = ModeType.SEARCH;
    }

    static List<Uri> extractSharedGalleryLinks(String text) {
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        if (text != null) {
            Matcher matcher = SHARED_GALLERY_LINK.matcher(text);
            while (matcher.find()) unique.add(matcher.group());
        }
        ArrayList<Uri> result = new ArrayList<>();
        for (String link : unique) result.add(Uri.parse(link));
        return result;
    }

    private void useBookmarkMode(Intent intent, String packageName) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            inspector = intent.getParcelableExtra(packageName + ".INSPECTOR", InspectorV3.class);
        } else {
            inspector = intent.getParcelableExtra(packageName + ".INSPECTOR");
        }
        if (inspector == null) {
            useNormalMode();
            return;
        }
        inspector.initialize(this, resetDataset);
        modeType = ModeType.BOOKMARK;
        ApiRequestType type = inspector.getRequestType();
        if (type == ApiRequestType.BYTAG) modeType = ModeType.TAG;
        else if (type == ApiRequestType.BYALL) modeType = ModeType.NORMAL;
        else if (type == ApiRequestType.BYSEARCH) modeType = ModeType.SEARCH;
        else if (type == ApiRequestType.FAVORITE) modeType = ModeType.FAVORITE;

    }

    private void useFavoriteMode(int page) {
        //instantiateWebView();
        inspector = InspectorV3.favoriteInspector(this, null, page, resetDataset);
        modeType = ModeType.FAVORITE;
    }

    private void useSearchMode(Intent intent, String packageName) {
        String query = intent.getStringExtra(packageName + ".QUERY");
        if (query == null) {
            useNormalMode();
            return;
        }
        boolean ok = tryOpenId(query);
        if (!ok) createSearchInspector(intent, packageName, query);
    }

    private void createSearchInspector(Intent intent, String packageName, String query) {
        boolean advanced = intent.getBooleanExtra(packageName + ".ADVANCED", false);
        ArrayList<Tag> tagArrayList = intent.getParcelableArrayListExtra(packageName + ".TAGS");
        Ranges ranges;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ranges = intent.getParcelableExtra(getPackageName() + ".RANGES", Ranges.class);
        } else {
            ranges = intent.getParcelableExtra(getPackageName() + ".RANGES");
        }
        if (query == null) {
            useNormalMode();
            return;
        }
        HashSet<Tag> tags = null;
        query = query.trim();
        if (advanced) {
            if (tagArrayList == null) {
                useNormalMode();
                return;
            }
            tags = new HashSet<>(tagArrayList);
        }
        inspector = InspectorV3.searchInspector(this, query, tags, 1, Global.getSortType(), ranges, resetDataset);
        modeType = ModeType.SEARCH;
    }

    private boolean tryOpenId(String query) {
        try {
            int id = Integer.parseInt(query);
            if (id <= 0) return false;
            inspector = InspectorV3.galleryInspector(this, id, startGallery);
            modeType = ModeType.ID;
            return true;
        } catch (NumberFormatException ignore) {
        }
        return false;
    }

    private void useTagMode(Intent intent, String packageName) {
        Tag t;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            t = intent.getParcelableExtra(packageName + ".TAG", Tag.class);
        } else {
            t = intent.getParcelableExtra(packageName + ".TAG");
        }
        if (t == null) {
            useNormalMode();
            return;
        }
        inspector = InspectorV3.tagInspector(this, t, 1, Global.getSortType(), resetDataset);
        modeType = ModeType.TAG;
    }

    /**
     * Load inspector from an URL, it can be either a tag or a search
     */
    private void manageDataStart(Uri data) {
        if (!isTrustedLink(data)) {
            useNormalMode();
            return;
        }
        List<String> datas = data.getPathSegments();
        TagType dataType;

        LogUtility.d("Datas: " + datas);
        if (datas.isEmpty()) {
            useNormalMode();
            return;
        }
        dataType = TagType.typeByName(datas.get(0));
        if (dataType != TagType.UNKNOWN) useDataTagMode(datas, dataType);
        else useDataSearchMode(data, datas);
    }

    private void useDataSearchMode(Uri data, List<String> datas) {
        boolean favorite = "favorites".equals(datas.get(0));
        if (!favorite && (data.getQueryParameter("q") == null || data.getQueryParameter("q").trim().isEmpty())) {
            useNormalMode();
            return;
        }
        int page = searchLinkPage(data);

        if (favorite) {
            if (AuthStore.hasOnlineIdentity(this)) useFavoriteMode(page);
            else {
                Intent intent = new Intent(this, FavoriteActivity.class);
                startActivity(intent);
                finish();
            }
            return;
        }

        inspector = searchLinkInspector(this, data, resetDataset);
        modeType = ModeType.SEARCH;
    }

    static int searchLinkPage(Uri data) {
        try {
            int page = Integer.parseInt(data.getQueryParameter("page"));
            return page > 0 ? page : 1;
        } catch (NumberFormatException exception) {
            return 1;
        }
    }

    static boolean isTrustedLink(Uri data) {
        if (data == null || data.getUserInfo() != null) return false;
        String scheme = data.getScheme();
        if (!"https".equalsIgnoreCase(scheme) && !"http".equalsIgnoreCase(scheme)) return false;
        if (!"nhentai.net".equalsIgnoreCase(data.getHost())) return false;
        int port = data.getPort();
        return port == -1 || ("https".equalsIgnoreCase(scheme) && port == 443)
            || ("http".equalsIgnoreCase(scheme) && port == 80);
    }

    static InspectorV3 searchLinkInspector(Context context, Uri data, InspectorV3.InspectorResponse response) {
        return InspectorV3.savedSearchInspector(context, data.getQueryParameter("q"), searchLinkPage(data),
            SortType.findFromAddition(data.getQueryParameter("sort")), response);
    }

    private void useDataTagMode(List<String> datas, TagType type) {
        if (datas.size() < 2 || datas.get(1).trim().isEmpty()) {
            useNormalMode();
            return;
        }
        String query = datas.get(1);
        Tag tag = Queries.TagTable.getTagFromTagName(query);
        if (tag == null)
            tag = new Tag(query, -1, SpecialTagIds.INVALID_ID, type, TagStatus.DEFAULT);
        SortType sortType = SortType.RECENT_ALL_TIME;
        if (datas.size() == 3) {
            sortType = SortType.findFromAddition(datas.get(2));
        }
        inspector = InspectorV3.tagInspector(this, tag, 1, sortType, resetDataset);
        modeType = ModeType.TAG;
    }

    public void hidePageSwitcher() {
        runOnUiThread(() -> pageSwitcher.setVisibility(View.GONE));
    }

    public void showPageSwitcher(final int actualPage, final int totalPage) {
        pageSwitcher.setPages(totalPage, actualPage);


        if (Global.isInfiniteScrollMain()) {
            hidePageSwitcher();
        }

    }
    @SuppressLint("NotifyDataSetChanged")
    @Override
    protected void onResume() {
        super.onResume();
        Login.initLogin(this);
        if (idOpenedGallery != -1) {
            adapter.updateColor(idOpenedGallery);
            idOpenedGallery = -1;
        }
        onlineFavoriteManager.setVisible(AuthStore.hasOnlineIdentity(this));
        SharedPreferences settings = getSharedPreferences("Settings", 0);
        LocaleListCompat setLocaleList = AppCompatDelegate.getApplicationLocales();
        settings.edit().putString(getString(R.string.preference_key_language),
            setLocaleList.isEmpty() ? getString(R.string.key_default_value) :
                Objects.requireNonNull(setLocaleList.get(0)).toLanguageTag()).apply();
        if (setting) {
            Global.initFromShared(this);//restart all settings
            inspector = inspector.cloneInspector(this, resetDataset);
            inspector.start();//restart inspector
            adapter.notifyDataSetChanged();//restart adapter
            adapter.resetStatuses();
            showPageSwitcher(inspector.getPage(), inspector.getPageCount());//restart page switcher
            changeLayout(getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE);
            setting = false;
        } else if (filteringTag) {
            inspector = InspectorV3.basicInspector(this, 1, resetDataset);
            inspector.start();
            filteringTag = false;
        }
        invalidateOptionsMenu();
        if (listGrid != null) listGrid.refresh();
    }


    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main, menu);
        popularItemDispay(menu.findItem(R.id.by_popular));

        showLanguageIcon(menu.findItem(R.id.only_language));
        listGrid.bindMenu(menu.findItem(R.id.list_columns));

        menu.findItem(R.id.only_language).setVisible(modeType == ModeType.NORMAL);
        menu.findItem(R.id.filter_loaded_results).setVisible(modeType != ModeType.ID);
        menu.findItem(R.id.random_favorite).setVisible(modeType == ModeType.FAVORITE);
        menu.findItem(R.id.save_search_plan).setVisible(canSaveSearchPlan());

        initializeSearchItem(menu.findItem(R.id.search));


        if (modeType == ModeType.TAG) {
            MenuItem item = menu.findItem(R.id.tag_manager);
            item.setVisible(inspector.getTag().getId() > 0);
            TagStatus ts = inspector.getTag().getStatus();
            updateTagStatus(item, ts);
        }
        Utility.tintMenu(this, menu);
        return true;
    }

    private void initializeSearchItem(MenuItem item) {
        if (modeType != ModeType.FAVORITE)
            item.setActionView(null);
        else {
            ((SearchView) Objects.requireNonNull(item.getActionView())).setOnQueryTextListener(new SearchView.OnQueryTextListener() {
                @Override
                public boolean onQueryTextSubmit(String query) {
                    inspector = InspectorV3.favoriteInspector(MainActivity.this, query, 1, resetDataset);
                    inspector.start();
                    Objects.requireNonNull(getSupportActionBar()).setTitle(query);
                    return true;
                }

                @Override
                public boolean onQueryTextChange(String newText) {
                    return false;
                }
            });
        }
    }

    private void popularItemDispay(MenuItem item) {
        item.setTitle(getString(R.string.sort_type_title_format, getString(currentSortType().getNameId())));
        Global.setTint(this, item.getIcon());
    }

    private SortType currentSortType() {
        return inspector != null && inspector.getSortType() != null ? inspector.getSortType() : Global.getSortType();
    }

    private void showLanguageIcon(MenuItem item) {
        switch (Global.getOnlyLanguage()) {
            case JAPANESE:
                item.setTitle(R.string.only_japanese);
                item.setIcon(R.drawable.ic_jpbw);
                break;
            case CHINESE:
                item.setTitle(R.string.only_chinese);
                item.setIcon(R.drawable.ic_cnbw);
                break;
            case ENGLISH:
                item.setTitle(R.string.only_english);
                item.setIcon(R.drawable.ic_gbbw);
                break;
            case ALL:
                item.setTitle(R.string.all_languages);
                item.setIcon(R.drawable.ic_world);
                break;
        }
        item.setTitle(getString(R.string.list_language_filter, item.getTitle()));
        Global.setTint(this, item.getIcon());
    }

    @Override
    protected int getPortraitColumnCount() {
        return Global.getColPortMain();
    }

    @Override
    protected int getLandscapeColumnCount() {
        return Global.getColLandMain();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        Intent i;
        LogUtility.d("Pressed item: " + item.getItemId());
        if (item.getItemId() == R.id.by_popular) {
            updateSortType(item);
        } else if (item.getItemId() == R.id.list_columns) {
            listGrid.cycle();
            return true;
        } else if (item.getItemId() == R.id.only_language) {
            updateLanguageAndIcon(item);
        } else if (item.getItemId() == R.id.search) {
            if (modeType != ModeType.FAVORITE) {//show textbox or start search activity
                i = new Intent(this, SearchActivity.class);
                startActivity(i);
            }
        } else if (item.getItemId() == R.id.open_browser) {
            if (inspector != null) {
                i = new Intent(Intent.ACTION_VIEW, Uri.parse(inspector.getUrl()));
                startActivity(i);
            }
        } else if (item.getItemId() == R.id.filter_loaded_results) {
            showLoadedFilterDialog(item);
        } else if (item.getItemId() == R.id.random_favorite) {
            inspector = InspectorV3.randomInspector(this, startGallery, true);
            inspector.start();
        } else if (item.getItemId() == R.id.download_page) {
            if (inspector.getGalleries() != null)
                showDialogDownloadAll();
        } else if (item.getItemId() == R.id.add_bookmark) {
            Queries.BookmarkTable.addBookmark(inspector);
        } else if (item.getItemId() == R.id.save_search_plan) {
            saveSearchPlan();
            return true;
        } else if (item.getItemId() == R.id.tag_manager) {
            TagStatus ts = TagV2.updateStatus(inspector.getTag());
            updateTagStatus(item, ts);
        } else if (item.getItemId() == android.R.id.home) {
            finish();
        }
        return super.onOptionsItemSelected(item);
    }

    private void saveSearchPlan() {
        if (!canSaveSearchPlan()) return;
        Uri url = Uri.parse(inspector.getUrl());
        String query = url.getQueryParameter("query");
        if (query == null) query = url.getQueryParameter("q");
        String sort = url.getQueryParameter("sort");
        ContentValues values = new ContentValues();
        values.put("query", query == null ? "" : query);
        values.put("sort", sort == null ? "" : sort);
        values.put("page", inspector.getPage());
        values.put("pinned", 0);
        EditText name = new EditText(this);
        name.setSingleLine(true);
        name.setHint(R.string.search_plan_name);
        androidx.appcompat.app.AlertDialog dialog = new MaterialAlertDialogBuilder(this)
            .setTitle(R.string.save_search_plan).setView(name)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok, null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            values.put("name", name.getText().toString().trim());
            if (!SearchPlanStore.isValid(values, false)) {
                name.setError(getString(R.string.search_plan_invalid));
                return;
            }
            view.setEnabled(false);
            SearchPlanStore.execute(() -> {
                try {
                    SearchPlanStore.insert(values);
                    runOnUiThread(() -> {
                        if (!isFinishing() && !isDestroyed()) dialog.dismiss();
                    });
                } catch (RuntimeException exception) {
                    LogUtility.e("Unable to save search plan", exception);
                    runOnUiThread(() -> {
                        if (isFinishing() || isDestroyed()) return;
                        view.setEnabled(true);
                        Toast.makeText(this, R.string.search_plan_storage_error, Toast.LENGTH_LONG).show();
                    });
                }
            });
        }));
        dialog.show();
    }

    private boolean canSaveSearchPlan() {
        if (inspector == null) return false;
        ApiRequestType type = inspector.getRequestType();
        return type == ApiRequestType.BYSEARCH || type == ApiRequestType.BYTAG;
    }

    private void showLoadedFilterDialog(MenuItem item) {
        if (adapter == null) return;
        String[] labels = {
            getString(R.string.filter_downloaded_only), getString(R.string.filter_favorite_only),
            getString(R.string.filter_reading_only), getString(R.string.filter_unread_only),
            getString(R.string.filter_custom_status_only)
        };
        boolean[] checked = {
            adapter.isLoadedDownloadedOnly(), adapter.isLoadedFavoriteOnly(),
            adapter.getLoadedReadingFilter() == 1, adapter.getLoadedReadingFilter() == 2,
            adapter.isLoadedCustomStatusOnly()
        };
        new MaterialAlertDialogBuilder(this)
            .setTitle(R.string.filter_loaded_results)
            .setMultiChoiceItems(labels, checked, (dialog, which, value) -> checked[which] = value)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.ok, (dialog, which) -> {
                int reading = checked[2] ? 1 : checked[3] ? 2 : 0;
                adapter.setLoadedFilters(checked[0], checked[1], reading, checked[4]);
            }).show();
    }

    private void updateSortType(MenuItem item) {
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.select_dialog_singlechoice);
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this);
        for (SortType type : SortType.values())
            adapter.add(getString(type.getNameId()));
        temporaryType = currentSortType();
        builder.setIcon(R.drawable.ic_sort).setTitle(R.string.sort_select_type);
        builder.setSingleChoiceItems(adapter, temporaryType.ordinal(), (dialog, which) -> temporaryType = SortType.values()[which]);
        builder.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                temporaryType = SortType.values()[position];
                parent.setSelection(position);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        builder.setPositiveButton(R.string.ok, (dialog, which) -> {
            Global.updateSortType(MainActivity.this, temporaryType);
            inspector = inspector.cloneInspector(MainActivity.this, resetDataset);
            inspector.setSortType(temporaryType);
            popularItemDispay(item);
            inspector.start();
        });
        builder.setNegativeButton(R.string.cancel, null);
        builder.show();
    }

    private void updateLanguageAndIcon(MenuItem item) {
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.select_dialog_singlechoice);
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this);

        adapter.addAll(Arrays.stream(Language.getFilteredValuesArray())
            .map(lang -> getString(lang.getNameResId()))
            .collect(Collectors.toList())
        );

        AtomicReference<Language> selectedLanguage = new AtomicReference<>(Global.getOnlyLanguage());
        builder.setIcon(R.drawable.ic_world)
            .setTitle(R.string.change_language)
            .setSingleChoiceItems(adapter, selectedLanguage.get().ordinal(),
                (dialog, which) -> selectedLanguage.set(Language.getFilteredValuesArray()[which]))
            .setPositiveButton(R.string.ok, (dialog, which) -> {
                Global.updateOnlyLanguage(MainActivity.this, Language.valueOf(selectedLanguage.get().name()));
                // wait 250ms to reduce the requests
                changeLanguageTimeHandler.removeCallbacks(changeLanguageRunnable);
                changeLanguageTimeHandler.postDelayed(changeLanguageRunnable, CHANGE_LANGUAGE_DELAY);
                showLanguageIcon(item);
            })
            .setNegativeButton(R.string.cancel, null)
            .show();
    }

    private void showDialogDownloadAll() {
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this);
        builder
            .setTitle(R.string.download_all_galleries_in_this_page)
            .setIcon(R.drawable.ic_file)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.ok, (dialog, which) -> {
                for (GenericGallery g : inspector.getGalleries())
                    DownloadGalleryV2.downloadGallery(MainActivity.this, g);
            });
        builder.show();
    }

    private void updateTagStatus(MenuItem item, TagStatus ts) {
        switch (ts) {
            case DEFAULT:
                item.setIcon(R.drawable.ic_help);
                break;
            case AVOIDED:
                item.setIcon(R.drawable.ic_close);
                break;
            case ACCEPTED:
                item.setIcon(R.drawable.ic_check);
                break;
        }
        Global.setTint(this, item.getIcon());
    }

    @Override
    public boolean onNavigationItemSelected(@NonNull MenuItem item) {
        Intent intent;
        if (item.getItemId() == R.id.downloaded) {
            if (Global.hasStoragePermission(this)) startLocalActivity();
            else requestStorage();
        } else if (item.getItemId() == R.id.bookmarks) {
            intent = new Intent(this, BookmarkActivity.class);
            startActivity(intent);
        } else if (item.getItemId() == R.id.history) {
            intent = new Intent(this, HistoryActivity.class);
            startActivity(intent);

        } else if (item.getItemId() == R.id.favorite_manager) {
            intent = new Intent(this, FavoriteActivity.class);
            startActivity(intent);
        } else if (item.getItemId() == R.id.action_settings) {
            setting = true;
            intent = new Intent(this, SettingsActivity.class);
            startActivity(intent);
        } else if (item.getItemId() == R.id.online_favorite_manager) {
            intent = new Intent(this, MainActivity.class);
            intent.putExtra(getPackageName() + ".FAVORITE", true);
            startActivity(intent);
        } else if (item.getItemId() == R.id.random) {
            intent = new Intent(this, RandomActivity.class);
            startActivity(intent);
        } else if (item.getItemId() == R.id.tag_manager) {
            intent = new Intent(this, TagFilterActivity.class);
            filteringTag = true;
            startActivity(intent);
        } else if (item.getItemId() == R.id.status_manager) {
            intent = new Intent(this, StatusViewerActivity.class);
            startActivity(intent);
        }
        //drawerLayout.closeDrawer(GravityCompat.START);
        return true;
    }

    private void requestStorage() {
        requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE}, 1);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        Global.initStorage(this);
        if (requestCode == 1 && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED)
            startLocalActivity();
        if (requestCode == 2 && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED)
            new VersionChecker(this, true);
    }

    private void startLocalActivity() {
        Intent i = new Intent(this, LocalActivity.class);
        startActivity(i);
    }

    /**
     * UNKNOWN in case of error
     * NORMAL when in main page
     * TAG when searching for a specific tag
     * FAVORITE when using online favorite button
     * SEARCH when used SearchActivity
     * BOOKMARK when loaded a bookmark
     * ID when searched for an ID
     */
    private enum ModeType {UNKNOWN, NORMAL, TAG, FAVORITE, SEARCH, BOOKMARK, ID}

    abstract class MainInspectorResponse extends InspectorV3.DefaultInspectorResponse {
        @Override
        public void onSuccess(List<GenericGallery> galleries) {
            super.onSuccess(galleries);
            if (adapter != null) adapter.resetStatuses();
            if (galleries.isEmpty())
                showError(R.string.no_entry_found, null);
        }

        @Override
        public void onStart() {
            runOnUiThread(() -> refresher.setRefreshing(true));
            hideError();
        }

        @Override
        public void onEnd() {
            runOnUiThread(() -> refresher.setRefreshing(false));
            inspecting = false;
        }

        @Override
        public void onFailure(Exception e) {
            super.onFailure(e);
            showError(R.string.unable_to_connect_to_the_site, v -> {
                inspector = inspector.cloneInspector(MainActivity.this, inspector.getResponse());
                inspector.start();
            });
        }

        @Override
        public boolean shouldStart(InspectorV3 inspector) {
            return true;
            //loadWebVewUrl(inspector.getUrl());
            //return inspector.canParseDocument();
        }
    }
}
