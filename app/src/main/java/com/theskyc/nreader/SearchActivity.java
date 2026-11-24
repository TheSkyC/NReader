package com.theskyc.nreader;

import android.content.Context;
import android.content.Intent;
import android.database.sqlite.SQLiteException;
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
    private final Ranges ranges = new Ranges();
    private final ArrayList<ChipTag> tags = new ArrayList<>();
    private final ArrayList<ChipTag> onlineChips = new ArrayList<>();
    private final ArrayList<ChipTag> onlineMergedChips = new ArrayList<>();
    private final Set<Integer> manualTagDecisions = new HashSet<>();
    private String onlineChipsIdentity;
    private long onlineChipsRevision = -1;
    private String onlineTagsLoadingIdentity;
    private final Runnable authObserver = () -> runOnUiThread(this::synchronizeOnlineSearchTags);
    private final Chip[] addChip = new Chip[TagType.values.length];
    private ChipGroup[] groups;
    private SearchView searchView;
    private HistoryAdapter adapter;
    private boolean advanced = false;
    private Ranges.TimeUnit temporaryUnit;
    private InputMethodManager inputMethodManager;
    private TagEntryDialog tagEntry;
    private final ExecutorService tagLoader = Executors.newSingleThreadExecutor();
    private long tagLabelGeneration;

    public void setQuery(String str, boolean submit) {
        runOnUiThread(() -> searchView.setQuery(str, submit));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        //Global.initActivity(this);
        setContentView(R.layout.activity_search);
        //init toolbar
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        assert getSupportActionBar() != null;
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        getSupportActionBar().setDisplayShowTitleEnabled(false);

        inputMethodManager = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);

        //find IDs
        searchView = findViewById(R.id.search);
        RecyclerView recyclerView = findViewById(R.id.recycler);
        findViewById(R.id.search_submit).setOnClickListener(view -> {
            advanced = true;
            submitSearch(searchView.getQuery().toString().trim(), false);
        });

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
                if (query.isEmpty() && !advanced) return true;
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
        searchView.requestFocus();
    }

    static boolean onlineTagsReady(String appliedIdentity, long appliedRevision,
                                   String currentIdentity, long currentRevision) {
        return currentIdentity != null && currentRevision >= 0 && currentRevision == appliedRevision
            && currentIdentity.equals(appliedIdentity);
    }

    private void submitSearch(String query, boolean withoutOnlineTags) {
        if (isResolvingTagEntry()) return;
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
        boolean useAdvanced = withoutOnlineTags || advanced || (scope != null
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
        if (isFinishing() || isDestroyed()) return;
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
            ChipGroup group = getGroup(tag.getType());
            Chip add = addChip[tag.getType().getId()];
            if (add != null) group.removeView(add);
            onlineChips.add(addChipTag(tag, true, true));
            if (add != null) group.addView(add);
        }
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
        ((TextView) uploadRangeLayout.findViewById(R.id.from_label)).setText(R.string.search_range_from_time);
        ((TextView) uploadRangeLayout.findViewById(R.id.to_label)).setText(R.string.search_range_to_time);
        TextView timeHelp = uploadRangeLayout.findViewById(R.id.range_help);
        timeHelp.setText(R.string.search_range_relative_time);
        timeHelp.setVisibility(View.VISIBLE);
        Button fromPage = pageRangeLayout.findViewById(R.id.fromButton);
        Button toPage = pageRangeLayout.findViewById(R.id.toButton);
        Button fromDate = uploadRangeLayout.findViewById(R.id.fromButton);
        Button toDate = uploadRangeLayout.findViewById(R.id.toButton);
        fromPage.setOnClickListener(v -> createPageBuilder(R.string.from_page, 0, 2000, ranges.getFromPage(), new DefaultDialogs.CustomDialogResults() {
            @Override
            public void positive(int actual) {
                ranges.setFromPage(actual);
                fromPage.setText(String.format(Locale.US, "%d", actual));
                if (ranges.getFromPage() > ranges.getToPage()) {
                    ranges.setToPage(Ranges.UNDEFINED);
                    toPage.setText(R.string.search_range_any);
                }
                advanced = true;
            }

            @Override
            public void neutral() {
                ranges.setFromPage(Ranges.UNDEFINED);
                fromPage.setText(R.string.search_range_any);
            }
        }));
        toPage.setOnClickListener(v -> createPageBuilder(R.string.to_page, ranges.getFromPage(), 2000, ranges.getToPage(), new DefaultDialogs.CustomDialogResults() {
            @Override
            public void positive(int actual) {
                ranges.setToPage(actual);
                toPage.setText(String.format(Locale.US, "%d", actual));
                advanced = true;
            }

            @Override
            public void neutral() {
                ranges.setToPage(Ranges.UNDEFINED);
                toPage.setText(R.string.search_range_any);
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
            createPageBuilder(from ? R.string.from_time : R.string.to_time, 1, temporaryUnit == Ranges.TimeUnit.YEAR ? 10 : 100, 1, new DefaultDialogs.CustomDialogResults() {
                @Override
                public void positive(int actual) {
                    if (from) {
                        ranges.setFromDateUnit(temporaryUnit);
                        ranges.setFromDate(actual);
                    } else {
                        ranges.setToDateUnit(temporaryUnit);
                        ranges.setToDate(actual);
                    }
                    button.setText(getString(R.string.search_range_time_value, actual, getString(temporaryUnit.getString())));
                    advanced = true;
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
        });
        builder.show();
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
                if (identity != null) addOnlineSearchTags(identity, revision, online);
                addTagButtons();
                refreshTagLabels();
            });
        });
    }

    private void addTagButtons() {
        //add + button
        for (TagType type : TagType.values) {
            //ignore these tags
            if (type == TagType.UNKNOWN || type == TagType.LANGUAGE || type == TagType.CATEGORY) {
                addChip[type.getId()] = null;
                continue;
            }
            ChipGroup cg = getGroup(type);
            Chip add = createAddChip(type, cg);
            addChip[type.getId()] = add;
            cg.addView(add);
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

    private Chip createAddChip(TagType type, ChipGroup group) {
        Chip c = (Chip) getLayoutInflater().inflate(R.layout.chip_layout, group, false);
        c.setCloseIconVisible(false);
        c.setChipIconResource(R.drawable.ic_add);
        c.setText(getString(R.string.add));
        c.setOnClickListener(v -> loadTag(type));
        Global.setTint(this, c.getChipIcon());
        return c;
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
        c.init(t, close, canBeAvoided);
        c.setOnCloseIconClickListener(v -> {
            cg.removeView(c);
            tags.remove(c);
            markManualTag(c);
            advanced = true;
        });
        c.setOnClickListener(v -> {
            markManualTag(c);
            c.updateStatus();
            advanced = true;
        });
        cg.addView(c);
        tags.add(c);
        return c;
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
            refreshTagLabels();
            return;
        }
        ChipGroup group = getGroup(tag.getType());
        Chip add = addChip[tag.getType().getId()];
        group.removeView(add);
        addChipTag(tag, true, true);
        group.addView(add);
        advanced = true;
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
        Utility.tintMenu(this, menu);
        return super.onCreateOptionsMenu(menu);

    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        } else if (item.getItemId() == R.id.view_groups) {
            View v = findViewById(R.id.groups);
            boolean isVisible = v.getVisibility() == View.VISIBLE;
            v.setVisibility(isVisible ? View.GONE : View.VISIBLE);
            findViewById(R.id.search_actions).setVisibility(isVisible ? View.GONE : View.VISIBLE);
            findViewById(R.id.recycler).setVisibility(isVisible ? View.VISIBLE : View.GONE);
            item.setIcon(isVisible ? R.drawable.ic_add : R.drawable.ic_close);
            Global.setTint(this, item.getIcon());
            if (!isVisible) {
                searchView.clearFocus();
                inputMethodManager.hideSoftInputFromWindow(searchView.getWindowToken(), 0);
            }
            return true;
        }
        return super.onOptionsItemSelected(item);
    }


}
