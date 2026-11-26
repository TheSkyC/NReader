// Modified for NReader by TheSkyC (2026); see NOTICE.
package com.theskyc.nreader;

import android.content.ContentValues;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.fragment.app.Fragment;
import androidx.viewpager2.adapter.FragmentStateAdapter;
import androidx.viewpager2.widget.ViewPager2;

import com.theskyc.nreader.adapters.TagsAdapter;
import com.theskyc.nreader.api.enums.TagStatus;
import com.theskyc.nreader.api.enums.TagType;
import com.theskyc.nreader.components.activities.GeneralActivity;
import com.theskyc.nreader.components.widgets.LiveSearchResults;
import com.theskyc.nreader.components.widgets.ResultsSearchView;
import com.theskyc.nreader.components.widgets.TagTypePage;
import com.theskyc.nreader.settings.DefaultDialogs;
import com.theskyc.nreader.settings.Global;
import com.theskyc.nreader.settings.Login;
import com.theskyc.nreader.settings.TagLanguage;
import com.theskyc.nreader.settings.TagV2;
import com.theskyc.nreader.async.database.TagRuleEdits;
import com.theskyc.nreader.async.database.TagViewStore;
import com.theskyc.nreader.utility.LogUtility;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.search.SearchBar;
import com.google.android.material.search.SearchView;
import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;
import com.google.android.material.textfield.TextInputLayout;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

public class TagFilterActivity extends GeneralActivity {
    private static final String STATE_SEARCH_EXPANDED = "tag_search_expanded";

    @Override
    protected int getBlackThemeStyle() {
        return R.style.AppTheme_Material3_Black;
    }

    static {
        AppCompatDelegate.setCompatVectorFromResourcesEnabled(true);
    }

    private SearchView searchView;
    private SearchBar searchBar;
    private ViewPager2 mViewPager;
    private String query = "";
    private boolean restoreSearchExpanded;
    private MenuItem sortFilterItem;
    private MenuItem minCountFilterItem;
    private MenuItem statusFilterItem;
    private MenuItem translationFilterItem;
    private MenuItem bulkEditItem;
    private MenuItem undoBulkEditItem;
    private MenuItem saveTagViewItem;
    private MenuItem savedTagViewsItem;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState != null) {
            query = savedInstanceState.getString("tag_query", "");
            restoreSearchExpanded = savedInstanceState.getBoolean(STATE_SEARCH_EXPANDED, false);
        }
        //Global.initActivity(this);
        setContentView(R.layout.activity_tag_filter);

        //init toolbar
        searchBar = findViewById(R.id.toolbar);
        setSupportActionBar(searchBar);
        ActionBar actionBar = Objects.requireNonNull(getSupportActionBar());
        actionBar.setDisplayHomeAsUpEnabled(true);
        actionBar.setDisplayShowTitleEnabled(false);
        searchView = findViewById(R.id.search_view);
        searchView.setupWithSearchBar(searchBar);
        configureSearchToolbar();
        LiveSearchResults.attach((ResultsSearchView) searchView, findViewById(R.id.search_results));
        searchView.getEditText().setHint(getString(R.string.search));
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

        // Create the adapter that will return a fragment for each of the three
        // primary sections of the activity.
        TagTypePageAdapter mTagTypePageAdapter = new TagTypePageAdapter(this);
        mViewPager = findViewById(R.id.container);
        mViewPager.setAdapter(mTagTypePageAdapter);
        mViewPager.setOffscreenPageLimit(1);
        TagRuleEdits.changes().observe(this, ignored -> updateBulkUndoItem());

        TabLayout tabLayout = findViewById(R.id.tabs);

        mViewPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                TagTypePage page = getFragment(position);
                if (page != null) page.refilter(query);
                updateBulkEditItem();
                updateTagViewItems();
            }
        });


        new TabLayoutMediator(tabLayout, mViewPager, (tab, position) -> {
            int id = 0;
            switch (position) {
                case 0:
                    id = R.string.applied_filters;
                    break;
                case 1:
                    id = R.string.tags;
                    break;
                case 2:
                    id = R.string.artists;
                    break;
                case 3:
                    id = R.string.characters;
                    break;
                case 4:
                    id = R.string.parodies;
                    break;
                case 5:
                    id = R.string.groups;
                    break;
                case 6:
                    id = R.string.online_tags;
                    break;
            }
            tab.setText(id);
        }).attach();
        mViewPager.setCurrentItem(getPage());
    }

    private void configureSearchToolbar() {
        int height = getResources().getDimensionPixelSize(R.dimen.tag_search_toolbar_height);
        View toolbar = searchView.getToolbar();
        toolbar.setMinimumHeight(height);
        ViewGroup.LayoutParams layoutParams = toolbar.getLayoutParams();
        if (layoutParams != null && layoutParams.height != height) {
            layoutParams.height = height;
            toolbar.setLayoutParams(layoutParams);
        }
    }

    @Override
    public void onConfigurationChanged(@NonNull android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (searchView != null) configureSearchToolbar();
    }

    public String getSearchQuery() {
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
        TagTypePage page = getActualFragment();
        if (page != null) page.refilter(query);
        updateBulkEditItem();
        updateTagViewItems();
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle state) {
        super.onSaveInstanceState(state);
        state.putString("tag_query", query);
        state.putBoolean(STATE_SEARCH_EXPANDED, searchView != null && searchView.isShowing());
    }

    @Nullable
    private TagTypePage getActualFragment() {
        return getFragment(mViewPager.getCurrentItem());
    }

    @Nullable
    private TagTypePage getFragment(int position) {
        return (TagTypePage) getSupportFragmentManager().findFragmentByTag("f" + position);
    }

    private int getPage() {
        Uri data = getIntent().getData();
        if (data != null) {
            List<String> params = data.getPathSegments();
            for (String x : params) LogUtility.i(x);
            if (!params.isEmpty()) {
                switch (params.get(0)) {
                    case "tags":
                        return 1;
                    case "artists":
                        return 2;
                    case "characters":
                        return 3;
                    case "parodies":
                        return 4;
                    case "groups":
                        return 5;
                }
            }
        }
        return 0;
    }

    private void updateSortItem(MenuItem item) {
        item.setIcon(TagV2.isSortedByName() ? R.drawable.ic_sort_by_alpha : R.drawable.ic_sort);
        item.setTitle(TagV2.isSortedByName() ? R.string.sort_by_title : R.string.sort_by_popular);
        Global.setTint(this, item.getIcon());
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_tag_filter, menu);
        sortFilterItem = menu.findItem(R.id.sort_by_name);
        updateSortItem(sortFilterItem);
        minCountFilterItem = menu.findItem(R.id.set_min_count);
        updateMinCountItem(minCountFilterItem);
        statusFilterItem = menu.findItem(R.id.filter_status);
        updateStatusFilterItem(statusFilterItem);
        translationFilterItem = menu.findItem(R.id.filter_translated);
        updateTranslationFilterItem(translationFilterItem);
        bulkEditItem = menu.findItem(R.id.bulk_edit_rules);
        updateBulkEditItem();
        undoBulkEditItem = menu.findItem(R.id.undo_bulk_edit);
        updateBulkUndoItem();
        saveTagViewItem = menu.findItem(R.id.save_tag_view);
        savedTagViewsItem = menu.findItem(R.id.saved_tag_views);
        updateTagViewItems();
        setSearchQuery(query);
        if (restoreSearchExpanded) {
            searchView.show();
            restoreSearchExpanded = false;
        }
        return true;
    }

    private void createDialog() {
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this);
        builder.setTitle(R.string.are_you_sure).setMessage(getString(R.string.clear_this_list)).setIcon(R.drawable.ic_help);
        builder.setPositiveButton(R.string.yes, (dialog, which) -> {
            TagTypePage page = getActualFragment();
            if (page != null) {
                page.reset();

            }
        }).setNegativeButton(R.string.no, null).setCancelable(true);
        builder.show();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();
        TagTypePage page = getActualFragment();
        if (id == R.id.reset_tags) createDialog();
        else if (id == R.id.recent_tags) showRecentTags();
        else if (id == R.id.save_tag_view) showSaveTagViewDialog();
        else if (id == R.id.saved_tag_views) showSavedTagViews();
        else if (id == R.id.reset_view_filters) {
            TagV2.resetFilterPreferences(this);
            if (sortFilterItem != null) updateSortItem(sortFilterItem);
            if (minCountFilterItem != null) updateMinCountItem(minCountFilterItem);
            if (statusFilterItem != null) updateStatusFilterItem(statusFilterItem);
            if (translationFilterItem != null) updateTranslationFilterItem(translationFilterItem);
            if (page != null) page.changeSize();
        }
        else if (id == R.id.set_min_count) minCountBuild();
        else if (id == R.id.filter_status) statusFilterBuild();
        else if (id == R.id.filter_translated) {
            TagV2.updateTranslatedOnly(this);
            if (translationFilterItem != null) updateTranslationFilterItem(translationFilterItem);
            if (page != null) page.refilter(query);
        }
        else if (id == R.id.bulk_edit_rules) showBulkEditDialog();
        else if (id == R.id.undo_bulk_edit) undoBulkEdit();
        else if (id == R.id.sort_by_name) {
            TagV2.updateSortByName(this);
            updateSortItem(item);
            if (page != null)
                page.refilter(query);
        }

        return super.onOptionsItemSelected(item);
    }

    private void minCountBuild() {
        int min = TagV2.getMinCount();
        DefaultDialogs.Builder builder = new DefaultDialogs.Builder(this);
        builder.setActual(min).setMax(100).setMin(2);
        builder.setYesbtn(R.string.ok).setNobtn(R.string.cancel);
        builder.setTitle(R.string.set_minimum_count).setDialogs(new DefaultDialogs.CustomDialogResults() {
            @Override
            public void positive(int actual) {
                LogUtility.d("ACTUAL: " + actual);
                TagV2.updateMinCount(TagFilterActivity.this, actual);
                if (minCountFilterItem != null) updateMinCountItem(minCountFilterItem);
                TagTypePage page = getActualFragment();
                if (page != null) {
                    page.changeSize();
                }
            }
        });
        DefaultDialogs.pageChangerDialog(builder);
    }

    private void statusFilterBuild() {
        TagStatus current = TagV2.getStatusFilter();
        TagStatus[] statuses = {null, TagStatus.DEFAULT, TagStatus.ACCEPTED, TagStatus.AVOIDED};
        String[] labels = {
            getString(R.string.filter_status_all),
            getString(R.string.filter_status_default),
            getString(R.string.filter_status_accepted),
            getString(R.string.filter_status_avoided)
        };
        int checked = 0;
        for (int index = 0; index < statuses.length; index++) {
            if (statuses[index] == current) {
                checked = index;
                break;
            }
        }
        new MaterialAlertDialogBuilder(this)
            .setTitle(R.string.filter_by_status)
            .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                TagV2.updateStatusFilter(this, statuses[which]);
                if (statusFilterItem != null) updateStatusFilterItem(statusFilterItem);
                TagTypePage page = getActualFragment();
                if (page != null) page.refilter(query);
                dialog.dismiss();
            })
            .setNegativeButton(R.string.cancel, null)
            .show();
    }

    private void updateStatusFilterItem(MenuItem item) {
        TagStatus status = TagV2.getStatusFilter();
        item.setTitle(status == null ? R.string.filter_by_status : status == TagStatus.DEFAULT
            ? R.string.filter_status_default : status == TagStatus.ACCEPTED
                ? R.string.filter_status_accepted : R.string.filter_status_avoided);
    }

    private void updateMinCountItem(MenuItem item) {
        item.setTitle(getString(R.string.set_minimum_count_format, TagV2.getMinCount()));
    }

    private void showRecentTags() {
        List<TagV2.RecentTag> recent = TagV2.getRecentTags(this);
        if (recent.isEmpty()) {
            new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.recent_tags)
                .setMessage(R.string.no_recent_tags)
                .setPositiveButton(R.string.ok, null)
                .show();
            return;
        }
        String[] labels = new String[recent.size()];
        for (int index = 0; index < recent.size(); index++) {
            TagV2.RecentTag tag = recent.get(index);
            labels[index] = tag.name + " (" + getString(typeLabel(tag.type)) + ")";
        }
        new MaterialAlertDialogBuilder(this)
            .setTitle(R.string.recent_tags)
            .setItems(labels, (dialog, which) -> {
                TagV2.RecentTag selected = recent.get(which);
                Integer page = pageForType(selected.type);
                if (page != null) mViewPager.setCurrentItem(page, false);
                if (searchView != null) setSearchQuery(selected.name);
                dialog.dismiss();
            })
            .setNeutralButton(R.string.clear_recent_tags, (dialog, which) -> {
                TagV2.clearRecentTags(this);
                dialog.dismiss();
            })
            .setNegativeButton(R.string.cancel, null)
            .show();
    }

    private int pageForType(TagType type) {
        return pageForTypeId(type.getId());
    }

    @Nullable
    private Integer pageForTypeId(int typeId) {
        switch (typeId) {
            case 1: return 4;
            case 2: return 3;
            case 3: return 1;
            case 4: return 2;
            case 5: return 5;
            default: return null;
        }
    }

    private int typeLabel(TagType type) {
        switch (type.getId()) {
            case 1: return R.string.parodies;
            case 2: return R.string.characters;
            case 3: return R.string.tags;
            case 4: return R.string.artists;
            case 5: return R.string.groups;
            default: return R.string.tags;
        }
    }

    private void updateTranslationFilterItem(MenuItem item) {
        item.setTitle(TagV2.isTranslatedOnly() ? R.string.filter_translated_only
            : R.string.filter_translated_all);
        item.setChecked(TagV2.isTranslatedOnly());
    }

    private void updateBulkEditItem() {
        if (bulkEditItem == null) return;
        TagTypePage page = getActualFragment();
        TagType type = page == null ? null : page.getTagType();
        boolean eligible = type != null && type != TagType.UNKNOWN && type != TagType.CATEGORY
            && !query.trim().isEmpty();
        bulkEditItem.setVisible(eligible);
    }

    private void updateTagViewItems() {
        if (saveTagViewItem == null) return;
        TagTypePage page = getActualFragment();
        TagType type = page == null ? null : page.getTagType();
        saveTagViewItem.setVisible(isSavedViewType(type));
        if (savedTagViewsItem != null) savedTagViewsItem.setVisible(true);
    }

    private static boolean isSavedViewType(@Nullable TagType type) {
        return type != null && type.getId() >= TagType.PARODY.getId()
            && type.getId() <= TagType.GROUP.getId();
    }

    private void showSaveTagViewDialog() {
        TagTypePage page = getActualFragment();
        TagType type = page == null ? null : page.getTagType();
        if (!isSavedViewType(type)) {
            updateTagViewItems();
            return;
        }
        View fields = getLayoutInflater().inflate(R.layout.dialog_tag_view_name, null);
        EditText name = fields.findViewById(android.R.id.edit);
        TextInputLayout nameInput = fields.findViewById(R.id.name_input);
        clearInputErrorOnTextChange(name, nameInput);
        androidx.appcompat.app.AlertDialog dialog = new MaterialAlertDialogBuilder(this)
            .setTitle(R.string.save_tag_view)
            .setView(fields)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok, null)
            .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
            .setOnClickListener(button -> {
                ContentValues values = buildTagView(name.getText().toString().trim(), type);
                if (!TagViewStore.isValid(values, false)) {
                    nameInput.setError(getString(R.string.tag_view_invalid_name));
                    return;
                }
                button.setEnabled(false);
                TagViewStore.execute(() -> {
                    try {
                        TagViewStore.insert(values);
                        runOnUiThread(() -> {
                            if (isFinishing() || isDestroyed()) return;
                            dialog.dismiss();
                            Toast.makeText(this, R.string.tag_view_saved, Toast.LENGTH_SHORT).show();
                        });
                    } catch (RuntimeException exception) {
                        LogUtility.e("Unable to save tag view", exception);
                        runOnUiThread(() -> {
                            if (isFinishing() || isDestroyed()) return;
                            button.setEnabled(true);
                            Toast.makeText(this, R.string.tag_view_storage_error, Toast.LENGTH_LONG).show();
                        });
                    }
                });
            }));
        dialog.show();
    }

    private ContentValues buildTagView(String name, @NonNull TagType type) {
        ContentValues values = new ContentValues();
        values.put("name", name);
        values.put("query", query);
        values.put("tag_type", type.getId());
        values.put("min_count", TagV2.getMinCount());
        values.put("sort_by_name", TagV2.isSortedByName() ? 1 : 0);
        TagStatus status = TagV2.getStatusFilter();
        values.put("status_filter", status == null ? -1 : status.ordinal());
        values.put("translated_only", TagV2.isTranslatedOnly() ? 1 : 0);
        values.put("pinned", 0);
        return values;
    }

    private static void clearInputErrorOnTextChange(@NonNull EditText editText,
            @NonNull TextInputLayout inputLayout) {
        editText.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence text, int start, int count, int after) { }

            @Override
            public void onTextChanged(CharSequence text, int start, int before, int count) { }

            @Override
            public void afterTextChanged(Editable text) {
                if (inputLayout.getError() != null) {
                    inputLayout.setError(null);
                    inputLayout.setErrorEnabled(false);
                }
            }
        });
    }

    private void showSavedTagViews() {
        showSavedTagViews(false);
    }

    private void showSavedTagViews(boolean manage) {
        TagViewStore.execute(() -> {
            List<ContentValues> views;
            try {
                views = TagViewStore.list();
            } catch (RuntimeException exception) {
                LogUtility.e("Unable to load tag views", exception);
                runOnUiThread(() -> Toast.makeText(this, R.string.tag_view_storage_error,
                    Toast.LENGTH_LONG).show());
                return;
            }
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (views.isEmpty()) {
                    new MaterialAlertDialogBuilder(this)
                        .setTitle(R.string.saved_tag_views)
                        .setMessage(R.string.no_saved_tag_views)
                        .setPositiveButton(R.string.ok, null)
                        .show();
                    return;
                }
                String[] labels = new String[views.size()];
                for (int index = 0; index < views.size(); index++) {
                    ContentValues view = views.get(index);
                    String label = view.getAsString("name");
                    labels[index] = view.getAsInteger("pinned") == 1
                        ? getString(R.string.tag_view_pinned_format, label) : label;
                }
                new MaterialAlertDialogBuilder(this)
                    .setTitle(manage ? R.string.manage_tag_views : R.string.saved_tag_views)
                    .setItems(labels, (dialog, which) -> {
                        if (manage) showTagViewActions(views.get(which));
                        else applyTagView(views.get(which));
                    })
                    .setNeutralButton(manage ? R.string.tag_view_load : R.string.manage_tag_views,
                        (dialog, which) -> showSavedTagViews(!manage))
                    .setNegativeButton(R.string.cancel, null)
                    .show();
            });
        });
    }

    private void showTagViewActions(ContentValues view) {
        boolean pinned = view.getAsInteger("pinned") == 1;
        String[] actions = {getString(R.string.tag_view_rename),
            getString(pinned ? R.string.tag_view_unpin : R.string.tag_view_pin), getString(R.string.delete)};
        new MaterialAlertDialogBuilder(this)
            .setTitle(view.getAsString("name"))
            .setItems(actions, (dialog, which) -> {
                if (which == 0) renameTagView(view);
                else if (which == 1) {
                    ContentValues updated = new ContentValues(view);
                    updated.remove("id");
                    updated.put("pinned", pinned ? 0 : 1);
                    changeTagView(view, updated);
                } else {
                    new MaterialAlertDialogBuilder(this)
                        .setTitle(R.string.tag_view_delete_title)
                        .setMessage(getString(R.string.tag_view_delete_message, view.getAsString("name")))
                        .setPositiveButton(R.string.delete, (confirmation, button) -> changeTagView(view, null))
                        .setNegativeButton(R.string.cancel, null)
                        .show();
                }
            })
            .setNegativeButton(R.string.cancel, null)
            .show();
    }

    private void renameTagView(ContentValues view) {
        View fields = getLayoutInflater().inflate(R.layout.dialog_tag_view_name, null);
        EditText name = fields.findViewById(android.R.id.edit);
        TextInputLayout nameInput = fields.findViewById(R.id.name_input);
        name.setText(view.getAsString("name"));
        clearInputErrorOnTextChange(name, nameInput);
        androidx.appcompat.app.AlertDialog dialog = new MaterialAlertDialogBuilder(this)
            .setTitle(R.string.tag_view_rename)
            .setView(fields)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.ok, null)
            .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
            .setOnClickListener(button -> {
                ContentValues updated = new ContentValues(view);
                updated.remove("id");
                updated.put("name", name.getText().toString().trim());
                if (!TagViewStore.isValid(updated, false)) {
                    nameInput.setError(getString(R.string.tag_view_invalid_name));
                    return;
                }
                dialog.dismiss();
                changeTagView(view, updated);
            }));
        dialog.show();
    }

    private void changeTagView(ContentValues expected, @Nullable ContentValues replacement) {
        TagViewStore.execute(() -> {
            try {
                boolean changed = replacement == null ? TagViewStore.deleteIfUnchanged(expected)
                    : TagViewStore.updateIfUnchanged(expected, replacement);
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    if (!changed) Toast.makeText(this, R.string.tag_view_changed, Toast.LENGTH_LONG).show();
                    showSavedTagViews(true);
                });
            } catch (RuntimeException exception) {
                LogUtility.e("Unable to change tag view", exception);
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed())
                        Toast.makeText(this, R.string.tag_view_storage_error, Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void applyTagView(ContentValues view) {
        Integer typeId = view.getAsInteger("tag_type");
        Integer page = typeId == null ? null : pageForTypeId(typeId);
        if (page == null || page < 0) return;
        Integer minCount = view.getAsInteger("min_count");
        Integer sortByName = view.getAsInteger("sort_by_name");
        Integer status = view.getAsInteger("status_filter");
        Integer translatedOnly = view.getAsInteger("translated_only");
        if (minCount == null || sortByName == null || status == null || translatedOnly == null
            || status < -1 || status >= TagStatus.values().length) return;
        TagV2.updateMinCount(this, minCount);
        TagV2.setSortByName(this, sortByName == 1);
        TagV2.updateStatusFilter(this, status < 0 ? null : TagStatus.values()[status]);
        TagV2.setTranslatedOnly(this, translatedOnly == 1);
        query = view.getAsString("query");
        if (query == null) query = "";
        mViewPager.setCurrentItem(page, false);
        if (searchView != null) setSearchQuery(query);
        updateSortItem(sortFilterItem);
        updateMinCountItem(minCountFilterItem);
        updateStatusFilterItem(statusFilterItem);
        updateTranslationFilterItem(translationFilterItem);
        updateTagViewItems();
        TagTypePage current = getActualFragment();
        if (current != null) current.refilter(query);
    }

    private void updateBulkUndoItem() {
        if (undoBulkEditItem != null) undoBulkEditItem.setVisible(TagRuleEdits.hasUndo());
    }

    private void showBulkEditDialog() {
        TagTypePage page = getActualFragment();
        TagType type = page == null ? null : page.getTagType();
        String selectedQuery = query.trim();
        if (type == null || type == TagType.UNKNOWN || type == TagType.CATEGORY || selectedQuery.isEmpty()) {
            updateBulkEditItem();
            return;
        }
        Locale locale = TagLanguage.current(this);
        TagRuleEdits.previewMatching(selectedQuery, type, TagV2.isSortedByName(), locale,
            TagV2.getStatusFilter(), TagV2.isTranslatedOnly(), result -> {
                TagTypePage currentPage = getActualFragment();
                if (isFinishing() || isDestroyed() || !Objects.equals(query, selectedQuery)
                    || currentPage == null || currentPage.getTagType() != type) return;
                if (result.outcome == TagRuleEdits.Outcome.PREVIEW) {
                    showBulkEditConfirmation(selectedQuery, type, locale, result.affected);
                } else if (result.outcome == TagRuleEdits.Outcome.TOO_MANY) {
                    android.widget.Toast.makeText(this,
                        getString(R.string.bulk_edit_too_many, TagRuleEdits.MAX_BULK_TAGS),
                        android.widget.Toast.LENGTH_LONG).show();
                } else if (result.outcome == TagRuleEdits.Outcome.NO_MATCHES) {
                    android.widget.Toast.makeText(this, R.string.bulk_edit_no_matches, android.widget.Toast.LENGTH_SHORT).show();
                } else if (result.outcome == TagRuleEdits.Outcome.FAILED) {
                    android.widget.Toast.makeText(this, R.string.local_tag_rule_save_error, android.widget.Toast.LENGTH_SHORT).show();
                }
            });
    }

    private void showBulkEditConfirmation(String selectedQuery, TagType type, Locale locale, int count) {
        String message = getString(R.string.bulk_edit_count_format, count, TagRuleEdits.MAX_BULK_TAGS);
        new MaterialAlertDialogBuilder(this)
            .setTitle(R.string.bulk_edit_rules)
            .setMessage(message)
            .setPositiveButton(R.string.bulk_set_accepted,
                (dialog, which) -> applyBulkEdit(selectedQuery, type, locale, TagStatus.ACCEPTED))
            .setNegativeButton(R.string.bulk_set_avoided,
                (dialog, which) -> applyBulkEdit(selectedQuery, type, locale, TagStatus.AVOIDED))
            .setNeutralButton(R.string.bulk_clear_rules,
                (dialog, which) -> applyBulkEdit(selectedQuery, type, locale, TagStatus.DEFAULT))
            .setCancelable(true)
            .show();
    }

    private void applyBulkEdit(String selectedQuery, TagType type, Locale locale, TagStatus status) {
        TagTypePage currentPage = getActualFragment();
        if (isFinishing() || isDestroyed() || !Objects.equals(query, selectedQuery)
            || currentPage == null || currentPage.getTagType() != type) return;
        TagRuleEdits.bulkSetMatching(selectedQuery, type, TagV2.isSortedByName(), locale,
            TagV2.getStatusFilter(), TagV2.isTranslatedOnly(), status, true, result -> {
                if (result.outcome == TagRuleEdits.Outcome.SAVED) {
                    android.widget.Toast.makeText(this,
                        getString(R.string.bulk_edit_saved_format, result.affected),
                        android.widget.Toast.LENGTH_SHORT).show();
                    TagTypePage page = getActualFragment();
                    if (page != null) page.refilter(query);
                } else if (result.outcome == TagRuleEdits.Outcome.LIMIT_REACHED) {
                    android.widget.Toast.makeText(this,
                        getResources().getQuantityString(R.plurals.tags_max_reached,
                            TagV2.MAXTAGS, TagV2.MAXTAGS),
                        android.widget.Toast.LENGTH_LONG).show();
                } else if (result.outcome == TagRuleEdits.Outcome.TOO_MANY) {
                    android.widget.Toast.makeText(this,
                        getString(R.string.bulk_edit_too_many, TagRuleEdits.MAX_BULK_TAGS),
                        android.widget.Toast.LENGTH_LONG).show();
                } else if (result.outcome == TagRuleEdits.Outcome.NO_MATCHES
                    || result.outcome == TagRuleEdits.Outcome.FAILED
                    || result.outcome == TagRuleEdits.Outcome.SUPERSEDED) {
                    android.widget.Toast.makeText(this, R.string.local_tag_rule_save_error,
                        android.widget.Toast.LENGTH_SHORT).show();
                }
            });
    }

    private void undoBulkEdit() {
        TagRuleEdits.undoLastBulk(result -> {
            updateBulkUndoItem();
            if (result.outcome == TagRuleEdits.Outcome.SAVED) {
                android.widget.Toast.makeText(this,
                    getString(R.string.bulk_undo_saved_format, result.affected),
                    android.widget.Toast.LENGTH_SHORT).show();
                TagTypePage page = getActualFragment();
                if (page != null) page.refilter(query);
            } else if (result.outcome == TagRuleEdits.Outcome.UNDO_STALE) {
                android.widget.Toast.makeText(this, R.string.bulk_undo_stale,
                    android.widget.Toast.LENGTH_LONG).show();
            } else if (result.outcome == TagRuleEdits.Outcome.UNDO_UNAVAILABLE) {
                android.widget.Toast.makeText(this, R.string.bulk_undo_unavailable,
                    android.widget.Toast.LENGTH_SHORT).show();
            } else if (result.outcome == TagRuleEdits.Outcome.FAILED
                || result.outcome == TagRuleEdits.Outcome.SUPERSEDED) {
                android.widget.Toast.makeText(this, R.string.local_tag_rule_save_error,
                    android.widget.Toast.LENGTH_SHORT).show();
            }
        });
    }


    static class TagTypePageAdapter extends FragmentStateAdapter {

        TagTypePageAdapter(TagFilterActivity activity) {
            super(activity.getSupportFragmentManager(), activity.getLifecycle());
        }

        @NonNull
        @Override
        public Fragment createFragment(int position) {
            return TagTypePage.newInstance(position);
        }

        @Override
        public int getItemCount() {
            return Login.isLogged() ? 7 : 6;
        }
    }
}
