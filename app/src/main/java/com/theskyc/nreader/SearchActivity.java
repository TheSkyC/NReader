package com.theskyc.nreader;

import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.database.sqlite.SQLiteException;
import android.icu.text.MeasureFormat;
import android.icu.util.Measure;
import android.icu.util.MeasureUnit;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Filter;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.AppCompatAutoCompleteTextView;
import androidx.appcompat.widget.SearchView;
import androidx.appcompat.widget.Toolbar;
import androidx.core.view.ViewCompat;
import androidx.core.widget.NestedScrollView;
import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.RecyclerView;

import com.theskyc.nreader.adapters.HistoryAdapter;
import com.theskyc.nreader.api.AccountSearchTags;
import com.theskyc.nreader.api.components.Ranges;
import com.theskyc.nreader.api.components.Tag;
import com.theskyc.nreader.api.components.TagSuggestion;
import com.theskyc.nreader.api.enums.Language;
import com.theskyc.nreader.api.enums.SpecialTagIds;
import com.theskyc.nreader.api.enums.TagStatus;
import com.theskyc.nreader.api.enums.TagType;
import com.theskyc.nreader.async.database.Queries;
import com.theskyc.nreader.async.database.TagCatalog;
import com.theskyc.nreader.async.database.TagTranslationCatalog;
import com.theskyc.nreader.components.activities.GeneralActivity;
import com.theskyc.nreader.components.widgets.ChipTag;
import com.theskyc.nreader.components.widgets.CustomLinearLayoutManager;
import com.theskyc.nreader.loginapi.LoadTags;
import com.theskyc.nreader.settings.DefaultDialogs;
import com.theskyc.nreader.settings.AuthStore;
import com.theskyc.nreader.settings.Global;
import com.theskyc.nreader.settings.Login;
import com.theskyc.nreader.settings.TagLanguage;
import com.theskyc.nreader.ui.TagLabel;
import com.theskyc.nreader.utility.LogUtility;
import com.theskyc.nreader.utility.Utility;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class SearchActivity extends GeneralActivity {
    static final String SEARCH_SUBMITTED_EXTRA = ".SEARCH_SUBMITTED";
    static final String ACCOUNT_TAGS_EXTRA = ".SEARCH_ACCOUNT_TAGS";
    static final String ACCOUNT_TAGS_EXPECTED_EXTRA = ".SEARCH_ACCOUNT_TAGS_EXPECTED";
    private Ranges ranges = new Ranges();
    private final ArrayList<ChipTag> tags = new ArrayList<>();
    private final ArrayList<ChipTag> onlineChips = new ArrayList<>();
    private final ArrayList<ChipTag> onlineMergedChips = new ArrayList<>();
    private final Set<Integer> manualTagDecisions = new HashSet<>();
    private String onlineChipsIdentity;
    private long onlineChipsRevision = -1;
    private String onlineTagsLoadingIdentity;
    private final Runnable authObserver = () -> runOnUiThread(this::synchronizeOnlineSearchTags);
    private ChipGroup[] groups;
    private TextView[] groupHeaders;
    private SearchView searchView;
    private HistoryAdapter adapter;
    private boolean advanced = false;
    private Ranges.TimeUnit temporaryUnit;
    private InputMethodManager inputMethodManager;
    private TagEntryDialog tagEntry;
    private final ExecutorService tagLoader = Executors.newSingleThreadExecutor();
    private long tagLabelGeneration;
    private ArrayList<Tag> restoredTags;
    private boolean[] restoredExpandedGroups;
    private int restoredScrollY;
    private boolean groupsPopulated;
    private boolean filtersVisible;
    private boolean showAllSelected;

    public void setQuery(String str, boolean submit) {
        runOnUiThread(() -> searchView.setQuery(str, submit));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        //Global.initActivity(this);
        setContentView(R.layout.activity_search);
        if (savedInstanceState != null) {
            Ranges savedRanges = savedInstanceState.getParcelable("search.ranges");
            if (savedRanges != null) ranges = savedRanges;
            restoredTags = savedInstanceState.getParcelableArrayList("search.tags");
            restoredExpandedGroups = savedInstanceState.getBooleanArray("search.expanded");
            restoredScrollY = savedInstanceState.getInt("search.scroll");
            filtersVisible = savedInstanceState.getBoolean("search.filtersVisible");
            showAllSelected = savedInstanceState.getBoolean("search.showAllSelected");
            advanced = savedInstanceState.getBoolean("search.advanced");
            int[] manualIds = savedInstanceState.getIntArray("search.manualIds");
            if (manualIds != null) for (int id : manualIds) manualTagDecisions.add(id);
        }
        //init toolbar
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        assert getSupportActionBar() != null;
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        getSupportActionBar().setDisplayShowTitleEnabled(false);

        inputMethodManager = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);

        //find IDs
        searchView = findViewById(R.id.search);
        searchView.setQueryHint(getString(R.string.search_keyword_hint));
        if (savedInstanceState != null)
            searchView.setQuery(savedInstanceState.getString("search.query", ""), false);
        RecyclerView recyclerView = findViewById(R.id.recycler);
        findViewById(R.id.search_submit).setEnabled(false);
        findViewById(R.id.search_clear_filters).setEnabled(false);
        findViewById(R.id.search_submit).setOnClickListener(view -> {
            advanced = true;
            submitSearch(searchView.getQuery().toString().trim(), false);
        });
        findViewById(R.id.search_selected_more).setOnClickListener(view -> {
            showAllSelected = !showAllSelected;
            updateFilterSummary();
        });
        findViewById(R.id.search_clear_filters).setOnClickListener(view ->
            new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.search_clear_filters)
                .setMessage(R.string.search_clear_filters_message)
                .setPositiveButton(R.string.search_clear_filters, (dialog, which) -> clearSearchFilters())
                .setNegativeButton(R.string.cancel, null)
                .show());

        groups = new ChipGroup[]{
            null,
            findViewById(R.id.parody_group),
            findViewById(R.id.character_group),
            findViewById(R.id.tag_group),
            findViewById(R.id.artist_group),
            findViewById(R.id.group_group),
            findViewById(R.id.language_group),
            findViewById(R.id.category_group),
        };
        groupHeaders = new TextView[]{
            null,
            findViewById(R.id.parody_header),
            findViewById(R.id.character_header),
            findViewById(R.id.tag_header),
            findViewById(R.id.artist_header),
            findViewById(R.id.group_header),
            findViewById(R.id.language_header),
            findViewById(R.id.category_header),
        };
        for (TagType type : TagType.values) {
            if (type == TagType.UNKNOWN) continue;
            ChipGroup group = getGroup(type);
            group.setVisibility(View.GONE);
            TextView header = groupHeaders[type.getId()];
            header.setOnClickListener(view -> {
                group.setVisibility(group.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
                updateGroupHeader(type);
            });
        }
        updateFilterSummary();
        applyFilterVisibility();
        initRanges();
        adapter = new HistoryAdapter(this);
        //init recyclerview
        recyclerView.setLayoutManager(new CustomLinearLayoutManager(this));
        recyclerView.setAdapter(adapter);
        recyclerView.addItemDecoration(new DividerItemDecoration(this, DividerItemDecoration.VERTICAL));


        searchView.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
            @Override
            public boolean onQueryTextSubmit(String query) {
                if (isResolvingTagEntry()) return true;
                query = query.trim();
                if (query.isEmpty() && !advanced && !filtersVisible) return true;
                submitSearch(query, false);
                return true;
            }

            @Override
            public boolean onQueryTextChange(String newText) {
                return false;
            }
        });

        populateGroup();
        TagTranslationCatalog.changes().observe(this, ignored -> {
            if (tagEntry != null) tagEntry.refreshSuggestions();
            refreshTagLabels();
        });
        AuthStore.observeIdentityChanges(authObserver);
        Login.observeOnlineTags(authObserver);
        synchronizeOnlineSearchTags();
        if (Login.wantsAccountTag() && !Login.hasCurrentOnlineTags()) loadOnlineSearchTags();
        if (!filtersVisible) searchView.requestFocus();
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putParcelable("search.ranges", ranges);
        outState.putBoolean("search.advanced", advanced);
        outState.putBoolean("search.filtersVisible", filtersVisible);
        outState.putBoolean("search.showAllSelected", showAllSelected);
        outState.putString("search.query", searchView.getQuery().toString());
        int[] manualIds = new int[manualTagDecisions.size()];
        int index = 0;
        for (int id : manualTagDecisions) manualIds[index++] = id;
        outState.putIntArray("search.manualIds", manualIds);
        if (groupsPopulated) {
            ArrayList<Tag> savedTags = new ArrayList<>();
            for (ChipTag chip : tags) {
                if (onlineChips.contains(chip)) continue;
                Tag tag = chip.getTag();
                TagStatus status = onlineMergedChips.contains(chip) ? TagStatus.DEFAULT : tag.getStatus();
                savedTags.add(new Tag(tag.getName(), tag.getCount(), tag.getId(), tag.getType(), status));
            }
            outState.putParcelableArrayList("search.tags", savedTags);
            boolean[] expanded = new boolean[groups.length];
            for (int i = 1; i < groups.length; i++) expanded[i] = groups[i].getVisibility() == View.VISIBLE;
            outState.putBooleanArray("search.expanded", expanded);
            outState.putInt("search.scroll", ((NestedScrollView) findViewById(R.id.groups)).getScrollY());
        } else {
            outState.putParcelableArrayList("search.tags", restoredTags);
            outState.putBooleanArray("search.expanded", restoredExpandedGroups);
            outState.putInt("search.scroll", restoredScrollY);
        }
    }

    static boolean onlineTagsReady(String appliedIdentity, long appliedRevision,
                                   String currentIdentity, long currentRevision) {
        return currentIdentity != null && currentRevision >= 0 && currentRevision == appliedRevision
            && currentIdentity.equals(appliedIdentity);
    }

    private void submitSearch(String query, boolean withoutOnlineTags) {
        if (isResolvingTagEntry() || (!groupsPopulated && (advanced || filtersVisible))) return;
        if ((advanced || filtersVisible || withoutOnlineTags)
            && (ranges.hasConflictingPageBounds() || ranges.hasConflictingUploadBounds())) {
            new MaterialAlertDialogBuilder(this)
                .setMessage(ranges.hasConflictingPageBounds()
                    ? R.string.search_page_range_conflict : R.string.search_upload_range_conflict)
                .setPositiveButton(R.string.ok, null)
                .show();
            return;
        }
        removeStaleOnlineTags();
        Login.OnlineTagScope scope = withoutOnlineTags ? null
            : Login.currentOnlineTagScope(onlineChipsIdentity, onlineChipsRevision);
        if (!withoutOnlineTags && scope == null && Login.wantsAccountTag()) {
            new MaterialAlertDialogBuilder(this)
                .setMessage(R.string.online_blacklist_search_unavailable)
                .setPositiveButton(R.string.online_blacklist_refresh, (dialog, which) -> loadOnlineSearchTags())
                .setNegativeButton(R.string.online_blacklist_search_without, (dialog, which) -> submitSearch(query, true))
                .setNeutralButton(R.string.cancel, null).show();
            return;
        }
        boolean useAdvanced = withoutOnlineTags || advanced || filtersVisible || (scope != null
            && (!onlineChips.isEmpty() || !onlineMergedChips.isEmpty()));
        if (!query.isEmpty()) adapter.addHistory(query);
        Intent intent = new Intent(this, MainActivity.class);
        intent.putExtra(getPackageName() + ".SEARCHMODE", true);
        intent.putExtra(getPackageName() + SEARCH_SUBMITTED_EXTRA, true);
        intent.putExtra(getPackageName() + ".QUERY", query);
        intent.putExtra(getPackageName() + ".ADVANCED", useAdvanced);
        ArrayList<Tag> automatic = new ArrayList<>();
        if (useAdvanced) {
            ArrayList<Tag> selected = new ArrayList<>(tags.size());
            for (ChipTag chip : tags) {
                Tag tag = chip.getTag();
                if (tag.getStatus() == TagStatus.DEFAULT) continue;
                if (onlineChips.contains(chip) || onlineMergedChips.contains(chip)) {
                    if (scope != null) automatic.add(tag);
                } else selected.add(tag);
            }
            intent.putParcelableArrayListExtra(getPackageName() + ".TAGS", selected);
            intent.putExtra(getPackageName() + ".RANGES", ranges);
        }
        if (scope != null) {
            intent.putExtra(getPackageName() + ACCOUNT_TAGS_EXPECTED_EXTRA, true);
            Bundle account = new Bundle();
            account.putBinder(getPackageName() + ACCOUNT_TAGS_EXTRA, new AccountSearchTags(scope, automatic));
            intent.putExtras(account);
        }
        startActivity(intent);
        finish();
    }

    private void loadOnlineSearchTags() {
        String requestedIdentity = AuthStore.activeIdentity(this);
        if (requestedIdentity == null) {
            android.widget.Toast.makeText(this, R.string.online_blacklist_sign_in_required,
                android.widget.Toast.LENGTH_LONG).show();
            return;
        }
        if (Objects.equals(requestedIdentity, onlineTagsLoadingIdentity)) return;
        onlineTagsLoadingIdentity = requestedIdentity;
        new LoadTags(this, successful -> {
            runOnUiThread(() -> {
                if (Objects.equals(requestedIdentity, onlineTagsLoadingIdentity)) onlineTagsLoadingIdentity = null;
                if (!successful && !isFinishing() && !isDestroyed())
                    android.widget.Toast.makeText(this, R.string.online_blacklist_load_error,
                        android.widget.Toast.LENGTH_SHORT).show();
            });
        }).start();
    }

    private void synchronizeOnlineSearchTags() {
        if (isFinishing() || isDestroyed() || !groupsPopulated) return;
        removeStaleOnlineTags();
        String identity = Login.currentOnlineTagsIdentity();
        long revision = Login.currentOnlineTagsRevision();
        if (!Login.useAccountTag() || identity == null || revision < 0
            || onlineTagsReady(onlineChipsIdentity, onlineChipsRevision, identity, revision)) return;
        try {
            tagLoader.execute(() -> {
                List<Tag> online = Queries.TagTable.getAllOnlineBlacklisted();
                runOnUiThread(() -> addOnlineSearchTags(identity, revision, online));
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // The activity was destroyed before the result was delivered.
        }
    }

    private void addOnlineSearchTags(String identity, long revision, List<Tag> online) {
        if (isFinishing() || isDestroyed() || !Login.useAccountTag()
            || !identity.equals(Login.currentOnlineTagsIdentity())
            || revision != Login.currentOnlineTagsRevision()) return;
        if (onlineTagsReady(onlineChipsIdentity, onlineChipsRevision, identity, revision)) return;
        removeStaleOnlineTags();
        onlineChipsIdentity = identity;
        onlineChipsRevision = revision;
        for (Tag tag : online) {
            if (manualTagDecisions.contains(tag.getId())) continue;
            ChipTag existing = findTagChip(tag);
            if (existing != null) {
                if (existing.getTag().getStatus() == TagStatus.DEFAULT) {
                    existing.changeStatus(TagStatus.AVOIDED);
                    onlineMergedChips.add(existing);
                }
                continue;
            }
            onlineChips.add(addChipTag(tag, true, true));
        }
        updateFilterSummary();
        refreshTagLabels();
    }

    private void createPageBuilder(int title, int min, int max, int actual, DefaultDialogs.DialogResults results) {
        min = Math.max(1, min);
        actual = Math.max(actual, min);
        DefaultDialogs.pageChangerDialog(new DefaultDialogs.Builder(this)
            .setTitle(title)
            .setMax(max)
            .setMin(min)
            .setDrawable(R.drawable.ic_search)
            .setActual(actual)
            .setYesbtn(R.string.ok)
            .setNobtn(R.string.cancel)
            .setMaybebtn(R.string.reset)
            .setDialogs(results));
    }

    private void initRanges() {
        LinearLayout pageRangeLayout = findViewById(R.id.page_range);
        LinearLayout uploadRangeLayout = findViewById(R.id.upload_range);
        ((TextView) pageRangeLayout.findViewById(R.id.title)).setText(R.string.page_range);
        ((TextView) uploadRangeLayout.findViewById(R.id.title)).setText(R.string.upload_time);
        ((TextView) pageRangeLayout.findViewById(R.id.from_label)).setText(R.string.search_range_min_pages);
        ((TextView) pageRangeLayout.findViewById(R.id.to_label)).setText(R.string.search_range_max_pages);
        ((TextView) pageRangeLayout.findViewById(R.id.range_error)).setText(R.string.search_page_range_conflict);
        ((TextView) uploadRangeLayout.findViewById(R.id.from_label)).setText(R.string.search_range_from_time);
        ((TextView) uploadRangeLayout.findViewById(R.id.to_label)).setText(R.string.search_range_to_time);
        TextView timeHelp = uploadRangeLayout.findViewById(R.id.range_help);
        timeHelp.setText(R.string.search_range_relative_time);
        timeHelp.setVisibility(View.VISIBLE);
        Button fromPage = pageRangeLayout.findViewById(R.id.fromButton);
        Button toPage = pageRangeLayout.findViewById(R.id.toButton);
        Button fromDate = uploadRangeLayout.findViewById(R.id.fromButton);
        Button toDate = uploadRangeLayout.findViewById(R.id.toButton);
        fromPage.setText(ranges.getFromPage() != Ranges.UNDEFINED
            ? String.valueOf(ranges.getFromPage()) : getString(R.string.search_range_any));
        toPage.setText(ranges.getToPage() != Ranges.UNDEFINED
            ? String.valueOf(ranges.getToPage()) : getString(R.string.search_range_any));
        fromDate.setText(R.string.search_range_any);
        toDate.setText(R.string.search_range_any);
        if (ranges.getFromDate() != Ranges.UNDEFINED && ranges.getFromDateUnit() != null)
            fromDate.setText(formatUploadAge(ranges.getFromDate(), ranges.getFromDateUnit()));
        if (ranges.getToDate() != Ranges.UNDEFINED && ranges.getToDateUnit() != null)
            toDate.setText(formatUploadAge(ranges.getToDate(), ranges.getToDateUnit()));
        fromPage.setOnClickListener(v -> createPageBuilder(R.string.from_page, 0, 2000, ranges.getFromPage(), new DefaultDialogs.CustomDialogResults() {
            @Override
            public void positive(int actual) {
                ranges.setFromPage(actual);
                fromPage.setText(String.format(Locale.US, "%d", actual));
                advanced = true;
                updateFilterSummary();
            }

            @Override
            public void neutral() {
                ranges.setFromPage(Ranges.UNDEFINED);
                fromPage.setText(R.string.search_range_any);
                updateFilterSummary();
            }
        }));
        toPage.setOnClickListener(v -> createPageBuilder(R.string.to_page, 1, 2000, ranges.getToPage(), new DefaultDialogs.CustomDialogResults() {
            @Override
            public void positive(int actual) {
                ranges.setToPage(actual);
                toPage.setText(String.format(Locale.US, "%d", actual));
                advanced = true;
                updateFilterSummary();
            }

            @Override
            public void neutral() {
                ranges.setToPage(Ranges.UNDEFINED);
                toPage.setText(R.string.search_range_any);
                updateFilterSummary();
            }
        }));
        fromDate.setOnClickListener(v -> showUnitDialog(fromDate, true));
        toDate.setOnClickListener(v -> showUnitDialog(toDate, false));
    }

    private void showUnitDialog(Button button, boolean from) {
        int i = 0;
        String[] strings = new String[Ranges.TimeUnit.values().length];
        for (Ranges.TimeUnit unit : Ranges.TimeUnit.values())
            strings[i++] = getString(unit.getString());

        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this);
        builder.setTitle(R.string.choose_unit);
        builder.setIcon(R.drawable.ic_search);
        builder.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_dropdown_item_1line, strings), (dialog, which) -> {
            temporaryUnit = Ranges.TimeUnit.values()[which];
            Ranges.TimeUnit previousUnit = from ? ranges.getFromDateUnit() : ranges.getToDateUnit();
            int previousValue = from ? ranges.getFromDate() : ranges.getToDate();
            int actual = temporaryUnit == previousUnit ? previousValue : 1;
            createPageBuilder(from ? R.string.search_range_from_time : R.string.search_range_to_time, 1, temporaryUnit == Ranges.TimeUnit.YEAR ? 10 : 100, actual, new DefaultDialogs.CustomDialogResults() {
                @Override
                public void positive(int actual) {
                    if (from) {
                        ranges.setFromDateUnit(temporaryUnit);
                        ranges.setFromDate(actual);
                    } else {
                        ranges.setToDateUnit(temporaryUnit);
                        ranges.setToDate(actual);
                    }
                    button.setText(formatUploadAge(actual, temporaryUnit));
                    advanced = true;
                    updateFilterSummary();
                }

                @Override
                public void neutral() {
                    if (from) {
                        ranges.setFromDateUnit(Ranges.UNDEFINED_DATE);
                        ranges.setFromDate(Ranges.UNDEFINED);
                    } else {
                        ranges.setToDateUnit(Ranges.UNDEFINED_DATE);
                        ranges.setToDate(Ranges.UNDEFINED);
                    }
                    button.setText(R.string.search_range_any);
                    updateFilterSummary();
                }
            });
        });
        builder.setNeutralButton(R.string.reset, (dialog, which) -> {
            if (from) {
                ranges.setFromDateUnit(Ranges.UNDEFINED_DATE);
                ranges.setFromDate(Ranges.UNDEFINED);
            } else {
                ranges.setToDateUnit(Ranges.UNDEFINED_DATE);
                ranges.setToDate(Ranges.UNDEFINED);
            }
            button.setText(R.string.search_range_any);
            updateFilterSummary();
        });
        builder.show();
    }

    private String formatUploadAge(int amount, Ranges.TimeUnit unit) {
        MeasureUnit measureUnit;
        switch (unit) {
            case HOUR: measureUnit = MeasureUnit.HOUR; break;
            case DAY: measureUnit = MeasureUnit.DAY; break;
            case WEEK: measureUnit = MeasureUnit.WEEK; break;
            case MONTH: measureUnit = MeasureUnit.MONTH; break;
            case YEAR: measureUnit = MeasureUnit.YEAR; break;
            default: throw new IllegalArgumentException("Unknown upload age unit: " + unit);
        }
        Locale locale = getResources().getConfiguration().getLocales().get(0);
        return MeasureFormat.getInstance(locale, MeasureFormat.FormatWidth.WIDE)
            .formatMeasures(new Measure(amount, measureUnit));
    }

    private void populateGroup() {
        tagLoader.execute(() -> {
            ArrayList<Tag> top = new ArrayList<>();
            for (TagType type : new TagType[]{TagType.TAG, TagType.PARODY, TagType.CHARACTER, TagType.ARTIST, TagType.GROUP})
                top.addAll(Queries.TagTable.getTopTags(type, Global.getFavoriteLimit(this)));
            ArrayList<Tag> filtered = new ArrayList<>(Queries.TagTable.getAllFiltered());
            ArrayList<Tag> categories = new ArrayList<>(Queries.TagTable.getTrueAllType(TagType.CATEGORY));
            ArrayList<Tag> languages = new ArrayList<>(Queries.TagTable.getTrueAllType(TagType.LANGUAGE));
            String identity = Login.useAccountTag() ? Login.currentOnlineTagsIdentity() : null;
            long revision = Login.currentOnlineTagsRevision();
            ArrayList<Tag> online = identity != null
                ? new ArrayList<>(Queries.TagTable.getAllOnlineBlacklisted()) : new ArrayList<>();
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (restoredTags != null) {
                    for (Tag tag : restoredTags) {
                        boolean editable = !tag.getType().equals(TagType.LANGUAGE)
                            && !tag.getType().equals(TagType.CATEGORY);
                        addChipTag(tag, editable, editable);
                    }
                } else {
                    for (Tag t : top) addChipTag(t, true, true);
                    for (Tag t : filtered) if (!tagAlreadyExist(t)) addChipTag(t, true, true);
                    for (Tag t : categories) addChipTag(t, false, false);
                    for (Tag t : languages) {
                        if ((t.getId() == SpecialTagIds.LANGUAGE_ENGLISH && Global.getOnlyLanguage() == Language.ENGLISH)
                            || (t.getId() == SpecialTagIds.LANGUAGE_JAPANESE && Global.getOnlyLanguage() == Language.JAPANESE)
                            || (t.getId() == SpecialTagIds.LANGUAGE_CHINESE && Global.getOnlyLanguage() == Language.CHINESE))
                            t.setStatus(TagStatus.ACCEPTED);
                        addChipTag(t, false, false);
                    }
                }
                addTagButtons();
                groupsPopulated = true;
                findViewById(R.id.search_submit).setEnabled(true);
                findViewById(R.id.search_clear_filters).setEnabled(true);
                if (identity != null) addOnlineSearchTags(identity, revision, online);
                synchronizeOnlineSearchTags();
                if (restoredExpandedGroups != null && restoredExpandedGroups.length == groups.length) {
                    for (int i = 1; i < groups.length; i++)
                        groups[i].setVisibility(restoredExpandedGroups[i] ? View.VISIBLE : View.GONE);
                } else {
                    for (ChipTag chip : tags) {
                        if (chip.getTag().getStatus() != TagStatus.DEFAULT)
                            getGroup(chip.getTag().getType()).setVisibility(View.VISIBLE);
                    }
                }
                restoredTags = null;
                updateFilterSummary();
                refreshTagLabels();
                if (restoredScrollY != 0) {
                    NestedScrollView scroll = findViewById(R.id.groups);
                    scroll.post(() -> scroll.scrollTo(0, restoredScrollY));
                }
            });
        });
    }

    private void addTagButtons() {
        int[] buttonIds = {0, R.id.parody_add, R.id.character_add, R.id.tag_add,
            R.id.artist_add, R.id.group_add};
        int[] titles = {0, R.string.parodies, R.string.characters, R.string.tags,
            R.string.artists, R.string.groups};
        for (int i = 1; i < buttonIds.length; i++) {
            TagType type = TagType.values[i];
            View add = findViewById(buttonIds[i]);
            add.setContentDescription(getString(R.string.search_add_tag_type, getString(titles[i])));
            add.setOnClickListener(view -> loadTag(type));
            add.setEnabled(true);
        }
    }

    @Override
    protected void onDestroy() {
        if (tagEntry != null) tagEntry.dismiss();
        AuthStore.removeIdentityObserver(authObserver);
        Login.removeOnlineTagsObserver(authObserver);
        tagLoader.shutdownNow();
        super.onDestroy();
    }

    private boolean tagAlreadyExist(Tag tag) {
        return findTagChip(tag) != null;
    }

    private ChipTag findTagChip(Tag tag) {
        for (ChipTag chip : tags) {
            Tag existing = chip.getTag();
            if (existing.getType() == tag.getType() && existing.equals(tag)) return chip;
        }
        return null;
    }

    private ChipTag addChipTag(Tag t, boolean close, boolean canBeAvoided) {
        ChipGroup cg = getGroup(t.getType());
        ChipTag c = (ChipTag) getLayoutInflater().inflate(R.layout.chip_layout_entry, cg, false);
        c.init(t, false, canBeAvoided);
        c.setEnsureMinTouchTargetSize(true);
        c.setOnClickListener(v -> showTagStatusDialog(c, close, canBeAvoided));
        cg.addView(c);
        tags.add(c);
        return c;
    }

    private void showTagStatusDialog(ChipTag chip, boolean removable, boolean canBeAvoided) {
        String[] choices = canBeAvoided
            ? new String[]{getString(R.string.search_tag_any), getString(R.string.search_tag_include),
                getString(R.string.search_tag_exclude)}
            : new String[]{getString(R.string.search_tag_any), getString(R.string.search_tag_include)};
        TagStatus current = chip.getTag().getStatus();
        int selected = current == TagStatus.ACCEPTED ? 1 : current == TagStatus.AVOIDED ? 2 : 0;
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this)
            .setTitle(chip.getText())
            .setSingleChoiceItems(choices, selected, (dialog, which) -> {
                markManualTag(chip);
                chip.changeStatus(which == 1 ? TagStatus.ACCEPTED
                    : which == 2 ? TagStatus.AVOIDED : TagStatus.DEFAULT);
                advanced = true;
                updateFilterSummary();
                dialog.dismiss();
            })
            .setNegativeButton(R.string.cancel, null);
        if (removable) builder.setNeutralButton(R.string.search_tag_remove, (dialog, which) -> {
            getGroup(chip.getTag().getType()).removeView(chip);
            tags.remove(chip);
            markManualTag(chip);
            advanced = true;
            updateFilterSummary();
        });
        builder.show();
    }

    private void updateChipAppearance(ChipTag chip) {
        TagStatus status = chip.getTag().getStatus();
        int state = status == TagStatus.ACCEPTED ? R.string.search_tag_include
            : status == TagStatus.AVOIDED ? R.string.search_tag_exclude : R.string.search_tag_any;
        if (onlineChips.contains(chip) || onlineMergedChips.contains(chip))
            state = R.string.search_tag_account_exclusion;
        ViewCompat.setStateDescription(chip, getString(state));
        int surface = MaterialColors.getColor(chip, com.google.android.material.R.attr.colorSurface);
        int accent = MaterialColors.getColor(chip, status == TagStatus.AVOIDED
            ? androidx.appcompat.R.attr.colorError : androidx.appcompat.R.attr.colorPrimary);
        chip.setChipBackgroundColor(ColorStateList.valueOf(MaterialColors.layer(surface, accent,
            status == TagStatus.DEFAULT ? 0.04f : 0.16f)));
    }

    private void updateFilterSummary() {
        findViewById(R.id.page_range).findViewById(R.id.range_error)
            .setVisibility(ranges.hasConflictingPageBounds() ? View.VISIBLE : View.GONE);
        findViewById(R.id.upload_range).findViewById(R.id.range_error)
            .setVisibility(ranges.hasConflictingUploadBounds() ? View.VISIBLE : View.GONE);
        int included = 0;
        int excluded = 0;
        ArrayList<ChipTag> selected = new ArrayList<>();
        ArrayList<ChipTag> automaticTags = new ArrayList<>();
        for (ChipTag chip : tags) {
            updateChipAppearance(chip);
            if (chip.getTag().getStatus() == TagStatus.ACCEPTED) included++;
            if (chip.getTag().getStatus() == TagStatus.AVOIDED) excluded++;
            if (chip.getTag().getStatus() != TagStatus.DEFAULT) {
                if (onlineChips.contains(chip) || onlineMergedChips.contains(chip)) automaticTags.add(chip);
                else selected.add(chip);
            }
        }
        selected.addAll(automaticTags);
        TextView summary = findViewById(R.id.search_filter_summary);
        summary.setText(included + excluded == 0 ? getString(R.string.search_filters_none)
            : getString(R.string.search_filters_summary, included, excluded));
        int automatic = onlineChips.size() + onlineMergedChips.size();
        TextView account = findViewById(R.id.search_account_filters);
        account.setVisibility(automatic == 0 ? View.GONE : View.VISIBLE);
        if (automatic != 0) account.setText(getString(R.string.search_account_filter_notice, automatic));
        ChipGroup selectedGroup = findViewById(R.id.search_selected_filters);
        selectedGroup.removeAllViews();
        if (ranges.getFromPage() != Ranges.UNDEFINED)
            addRangeSummary(selectedGroup, getString(R.string.search_range_min_pages),
                String.valueOf(ranges.getFromPage()), () -> ranges.setFromPage(Ranges.UNDEFINED));
        if (ranges.getToPage() != Ranges.UNDEFINED)
            addRangeSummary(selectedGroup, getString(R.string.search_range_max_pages),
                String.valueOf(ranges.getToPage()), () -> ranges.setToPage(Ranges.UNDEFINED));
        if (ranges.getFromDate() != Ranges.UNDEFINED && ranges.getFromDateUnit() != null)
            addRangeSummary(selectedGroup, getString(R.string.search_range_from_time),
                formatUploadAge(ranges.getFromDate(), ranges.getFromDateUnit()), () -> {
                    ranges.setFromDate(Ranges.UNDEFINED);
                    ranges.setFromDateUnit(Ranges.UNDEFINED_DATE);
                });
        if (ranges.getToDate() != Ranges.UNDEFINED && ranges.getToDateUnit() != null)
            addRangeSummary(selectedGroup, getString(R.string.search_range_to_time),
                formatUploadAge(ranges.getToDate(), ranges.getToDateUnit()), () -> {
                    ranges.setToDate(Ranges.UNDEFINED);
                    ranges.setToDateUnit(Ranges.UNDEFINED_DATE);
                });
        int limit = showAllSelected ? selected.size() : Math.min(selected.size(), 6);
        for (int i = 0; i < limit; i++) {
            ChipTag original = selected.get(i);
            boolean automaticTag = onlineChips.contains(original) || onlineMergedChips.contains(original);
            Chip summaryChip = (Chip) getLayoutInflater().inflate(R.layout.chip_layout, selectedGroup, false);
            int state = automaticTag ? R.string.search_tag_account_exclusion
                : original.getTag().getStatus() == TagStatus.ACCEPTED
                    ? R.string.search_tag_include : R.string.search_tag_exclude;
            summaryChip.setText(getString(R.string.search_selected_filter, getString(state), original.getText()));
            summaryChip.setEnsureMinTouchTargetSize(true);
            summaryChip.setCloseIconVisible(!automaticTag);
            if (!automaticTag) {
                summaryChip.setCloseIconContentDescription(getString(R.string.search_remove_filter, original.getText()));
                summaryChip.setOnCloseIconClickListener(view -> {
                    markManualTag(original);
                    original.changeStatus(TagStatus.DEFAULT);
                    advanced = true;
                    updateFilterSummary();
                });
            }
            TagType type = original.getTag().getType();
            boolean editable = !type.equals(TagType.CATEGORY) && !type.equals(TagType.LANGUAGE);
            summaryChip.setOnClickListener(view -> showTagStatusDialog(original, editable, editable));
            selectedGroup.addView(summaryChip);
        }
        Button more = findViewById(R.id.search_selected_more);
        more.setVisibility(selected.size() > 6 ? View.VISIBLE : View.GONE);
        if (selected.size() > 6) more.setText(showAllSelected ? getString(R.string.search_selected_show_less)
            : getString(R.string.search_selected_show_all, selected.size()));
        for (TagType type : TagType.values) {
            if (type != TagType.UNKNOWN) updateGroupHeader(type);
        }
    }

    private void addRangeSummary(ChipGroup group, String label, String value, Runnable clear) {
        Chip chip = (Chip) getLayoutInflater().inflate(R.layout.chip_layout, group, false);
        String text = getString(R.string.search_selected_filter, label, value);
        chip.setText(text);
        chip.setEnsureMinTouchTargetSize(true);
        chip.setCloseIconVisible(true);
        chip.setCloseIconContentDescription(getString(R.string.search_remove_filter, text));
        chip.setOnCloseIconClickListener(view -> {
            clear.run();
            advanced = true;
            initRanges();
            updateFilterSummary();
        });
        group.addView(chip);
    }

    private void updateGroupHeader(TagType type) {
        int count = 0;
        for (ChipTag chip : tags) {
            if (chip.getTag().getType().equals(type) && chip.getTag().getStatus() != TagStatus.DEFAULT) count++;
        }
        int[] titles = {0, R.string.parodies, R.string.characters, R.string.tags,
            R.string.artists, R.string.groups, R.string.languages, R.string.categories};
        TextView header = groupHeaders[type.getId()];
        String title = getString(titles[type.getId()]);
        header.setText(count == 0 ? title : getString(R.string.search_filter_section_count, title, count));
        header.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0,
            getGroup(type).getVisibility() == View.VISIBLE ? R.drawable.ic_comment_chevron_down
                : R.drawable.ic_keyboard_arrow_right, 0);
        header.setCompoundDrawableTintList(ColorStateList.valueOf(
            MaterialColors.getColor(header, com.google.android.material.R.attr.colorOnSurface)));
        ViewCompat.setStateDescription(header, getString(getGroup(type).getVisibility() == View.VISIBLE
            ? R.string.search_filter_expanded : R.string.search_filter_collapsed));
    }

    private void refreshTagLabels() {
        long generation = ++tagLabelGeneration;
        ArrayList<Integer> ids = new ArrayList<>();
        for (ChipTag chip : tags) {
            if (chip.getTag().getId() > 0) ids.add(chip.getTag().getId());
        }
        Locale locale = TagLanguage.current(this);
        try {
            tagLoader.execute(() -> {
                Map<Integer, String> names;
                try {
                    names = Queries.TagTable.getDisplayNames(ids, locale);
                } catch (RuntimeException error) {
                    LogUtility.w("Could not load search tag translations", error);
                    names = Collections.emptyMap();
                }
                Map<Integer, String> displayNames = names;
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed() || generation != tagLabelGeneration
                        || !locale.equals(TagLanguage.current(this))) return;
                    for (ChipTag chip : tags) {
                        Tag tag = chip.getTag();
                        chip.setText(TagLabel.format(chip, tag.getName(), displayNames.get(tag.getId())));
                    }
                    updateFilterSummary();
                });
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // The activity was destroyed before the refresh could be scheduled.
        }
    }

    private void markManualTag(ChipTag chip) {
        int tagId = chip.getTag().getId();
        if (tagId > 0) manualTagDecisions.add(tagId);
        onlineChips.remove(chip);
        onlineMergedChips.remove(chip);
    }

    private void clearSearchFilters() {
        for (ChipTag chip : onlineChips) {
            getGroup(chip.getTag().getType()).removeView(chip);
            tags.remove(chip);
        }
        onlineChips.clear();
        onlineMergedChips.clear();
        onlineChipsIdentity = null;
        onlineChipsRevision = -1;
        manualTagDecisions.clear();
        for (ChipTag chip : tags) chip.changeStatus(TagStatus.DEFAULT);
        ranges = new Ranges();
        initRanges();
        advanced = false;
        showAllSelected = false;
        updateFilterSummary();
        synchronizeOnlineSearchTags();
    }

    private void removeStaleOnlineTags() {
        if (onlineChipsIdentity == null || (Login.isAccountTagEnabled()
            && Objects.equals(onlineChipsIdentity, Login.currentOnlineTagsIdentity())
            && onlineChipsRevision == Login.currentOnlineTagsRevision())) return;
        for (ChipTag chip : onlineChips) {
            getGroup(chip.getTag().getType()).removeView(chip);
            tags.remove(chip);
        }
        for (ChipTag chip : onlineMergedChips) chip.changeStatus(TagStatus.DEFAULT);
        onlineChips.clear();
        onlineMergedChips.clear();
        onlineChipsIdentity = null;
        onlineChipsRevision = -1;
        updateFilterSummary();
    }

    private final class TagSuggestionAdapter extends ArrayAdapter<TagSuggestion> {
        private final TagType type;
        private final AppCompatAutoCompleteTextView entry;
        private final Filter filter = new Filter() {
            @Override
            protected FilterResults performFiltering(CharSequence constraint) {
                FilterResults results = new FilterResults();
                Locale locale = TagLanguage.current(SearchActivity.this);
                List<TagSuggestion> suggestions = constraint == null || constraint.length() == 0
                    ? new ArrayList<>()
                    : Queries.TagTable.getTagSuggestions(type, constraint.toString(), locale);
                results.values = new TagSuggestionResults(locale, suggestions);
                results.count = suggestions.size();
                return results;
            }

            @Override
            protected void publishResults(CharSequence constraint, FilterResults results) {
                if (!Objects.equals(constraint == null ? "" : constraint.toString(), entry.getText().toString())) return;
                TagSuggestionResults result = (TagSuggestionResults) results.values;
                if (result == null || !result.locale.equals(TagLanguage.current(SearchActivity.this))) return;
                clear();
                addAll(result.suggestions);
                if (results.count > 0) notifyDataSetChanged();
                else notifyDataSetInvalidated();
            }

            @Override
            public CharSequence convertResultToString(Object resultValue) {
                return ((TagSuggestion) resultValue).getTag().getName();
            }
        };

        TagSuggestionAdapter(TagType type, AppCompatAutoCompleteTextView entry) {
            super(SearchActivity.this, android.R.layout.simple_dropdown_item_1line);
            this.type = type;
            this.entry = entry;
        }

        @NonNull
        @Override
        public View getView(int position, View convertView, @NonNull ViewGroup parent) {
            TextView row = convertView == null
                ? (TextView) LayoutInflater.from(getContext()).inflate(android.R.layout.simple_dropdown_item_1line, parent, false)
                : (TextView) convertView;
            TagSuggestion suggestion = getItem(position);
            row.setText(suggestion == null ? null
                : TagLabel.format(parent, suggestion.getTag().getName(), suggestion.getDisplayName()));
            return row;
        }

        @Override
        public Filter getFilter() {
            return filter;
        }
    }

    private static final class TagSuggestionResults {
        final Locale locale;
        final List<TagSuggestion> suggestions;

        TagSuggestionResults(Locale locale, List<TagSuggestion> suggestions) {
            this.locale = locale;
            this.suggestions = suggestions;
        }
    }

    private void loadTag(TagType type) {
        if (tagEntry == null) tagEntry = new TagEntryDialog(type);
    }

    private ChipGroup getGroup(TagType type) {
        return groups[type.getId()];
    }

    private boolean isResolvingTagEntry() {
        return tagEntry != null && tagEntry.lookup != null;
    }

    private void addSearchTag(Tag tag) {
        ChipTag existing = findTagChip(tag);
        if (existing != null) {
            if (tag.getId() > 0) existing.refreshTag(tag);
            if (existing.getTag().getStatus() == TagStatus.DEFAULT) {
                markManualTag(existing);
                existing.changeStatus(TagStatus.ACCEPTED);
                advanced = true;
            }
            getGroup(tag.getType()).setVisibility(View.VISIBLE);
            updateFilterSummary();
            refreshTagLabels();
            return;
        }
        ChipGroup group = getGroup(tag.getType());
        tag.setStatus(TagStatus.ACCEPTED);
        ChipTag added = addChipTag(tag, true, true);
        markManualTag(added);
        group.setVisibility(View.VISIBLE);
        advanced = true;
        updateFilterSummary();
        refreshTagLabels();
    }

    private final class TagEntryDialog {
        private final TagType type;
        private final AppCompatAutoCompleteTextView entry;
        private final TagSuggestionAdapter suggestions;
        private final AlertDialog dialog;
        private Tag selected;
        private Future<?> lookup;

        TagEntryDialog(TagType type) {
            this.type = type;
            entry = (AppCompatAutoCompleteTextView) getLayoutInflater().inflate(
                R.layout.autocomplete_entry, findViewById(R.id.appbar), false);
            suggestions = new TagSuggestionAdapter(type, entry);
            entry.setAdapter(suggestions);
            entry.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) {}
                @Override public void onTextChanged(CharSequence text, int start, int before, int count) {
                    selected = null;
                }
                @Override public void afterTextChanged(Editable text) {}
            });
            entry.setOnItemClickListener((parent, view, position, id) -> {
                Object item = parent.getItemAtPosition(position);
                selected = item instanceof TagSuggestion ? ((TagSuggestion) item).getTag() : null;
            });
            entry.setOnEditorActionListener((view, actionId, event) -> {
                if (actionId != EditorInfo.IME_ACTION_SEND) return false;
                resolve();
                return true;
            });
            dialog = new MaterialAlertDialogBuilder(SearchActivity.this)
                .setView(entry)
                .setTitle(R.string.insert_tag_name)
                .setPositiveButton(R.string.ok, null)
                .setNegativeButton(R.string.cancel, null)
                .show();
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> resolve());
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(view -> dismiss());
            dialog.setOnCancelListener(ignored -> retire());
            dialog.setOnDismissListener(ignored -> retire());
            entry.requestFocus();
            inputMethodManager.showSoftInput(entry, InputMethodManager.SHOW_IMPLICIT);
        }

        private void refreshSuggestions() {
            if (lookup != null || !dialog.isShowing() || entry.length() == 0) return;
            String query = entry.getText().toString();
            suggestions.getFilter().filter(query, count -> {
                if (tagEntry == this && lookup == null && dialog.isShowing() && entry.hasFocus()
                    && query.equals(entry.getText().toString()) && count > 0) entry.showDropDown();
            });
        }

        private void resolve() {
            if (tagEntry != this || lookup != null || isFinishing() || isDestroyed()) return;
            String entered = entry.getText().toString();
            int selectedId = selected != null && selected.getName().equals(entered) ? selected.getId() : 0;
            String name = entered.trim();
            if (!TagCatalog.isCanonicalName(name)) {
                entry.setError(getString(R.string.search_tag_invalid_name));
                return;
            }
            entry.dismissDropDown();
            entry.setEnabled(false);
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
            lookup = tagLoader.submit(() -> {
                Tag tag = null;
                boolean failed = false;
                try {
                    tag = selectedId > 0 ? Queries.TagTable.getTagById(selectedId)
                        : Queries.TagTable.searchTypedName(name, type);
                    failed = selectedId > 0 && (tag == null || tag.getType() != type);
                } catch (SQLiteException | IllegalStateException error) {
                    failed = true;
                    LogUtility.w("Could not resolve a search tag", error);
                }
                Tag resolved = tag;
                boolean unavailable = failed;
                runOnUiThread(() -> complete(resolved, name, unavailable));
            });
        }

        private void complete(Tag tag, String name, boolean unavailable) {
            if (tagEntry != this || lookup == null) return;
            lookup = null;
            if (isFinishing() || isDestroyed() || !dialog.isShowing()) return;
            if (unavailable) {
                entry.setEnabled(true);
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                entry.requestFocus();
                entry.setError(getString(R.string.search_tag_resolve_failed));
                return;
            }
            addSearchTag(tag != null ? tag
                : new Tag(name.toLowerCase(Locale.US), 0, SpecialTagIds.INVALID_ID, type, TagStatus.ACCEPTED));
            inputMethodManager.hideSoftInputFromWindow(entry.getWindowToken(), InputMethodManager.HIDE_NOT_ALWAYS);
            dismiss();
        }

        private void dismiss() {
            retire();
            entry.dismissDropDown();
            dialog.dismiss();
        }

        private void retire() {
            if (tagEntry == this) tagEntry = null;
            if (lookup != null) lookup.cancel(false);
            lookup = null;
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.search, menu);
        MenuItem filterItem = menu.findItem(R.id.view_groups);
        filterItem.setIcon(filtersVisible ? R.drawable.ic_close : R.drawable.ic_filter_list);
        filterItem.setTitle(filtersVisible ? R.string.search_hide_filters : R.string.search_show_filters);
        Utility.tintMenu(this, menu);
        return super.onCreateOptionsMenu(menu);

    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        } else if (item.getItemId() == R.id.view_groups) {
            filtersVisible = !filtersVisible;
            applyFilterVisibility();
            item.setIcon(filtersVisible ? R.drawable.ic_close : R.drawable.ic_filter_list);
            item.setTitle(filtersVisible ? R.string.search_hide_filters : R.string.search_show_filters);
            Global.setTint(this, item.getIcon());
            if (filtersVisible) {
                searchView.clearFocus();
                inputMethodManager.hideSoftInputFromWindow(searchView.getWindowToken(), 0);
            }
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void applyFilterVisibility() {
        findViewById(R.id.groups).setVisibility(filtersVisible ? View.VISIBLE : View.GONE);
        findViewById(R.id.search_actions).setVisibility(filtersVisible ? View.VISIBLE : View.GONE);
        findViewById(R.id.recycler).setVisibility(filtersVisible ? View.GONE : View.VISIBLE);
    }


}
