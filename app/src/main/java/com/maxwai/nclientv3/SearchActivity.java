package com.maxwai.nclientv3;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.AppCompatAutoCompleteTextView;
import androidx.appcompat.widget.SearchView;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.RecyclerView;

import com.maxwai.nclientv3.adapters.HistoryAdapter;
import com.maxwai.nclientv3.api.components.Ranges;
import com.maxwai.nclientv3.api.components.Tag;
import com.maxwai.nclientv3.api.enums.Language;
import com.maxwai.nclientv3.api.enums.SpecialTagIds;
import com.maxwai.nclientv3.api.enums.TagStatus;
import com.maxwai.nclientv3.api.enums.TagType;
import com.maxwai.nclientv3.async.database.Queries;
import com.maxwai.nclientv3.components.activities.GeneralActivity;
import com.maxwai.nclientv3.components.widgets.ChipTag;
import com.maxwai.nclientv3.components.widgets.CustomLinearLayoutManager;
import com.maxwai.nclientv3.loginapi.LoadTags;
import com.maxwai.nclientv3.settings.DefaultDialogs;
import com.maxwai.nclientv3.settings.AuthStore;
import com.maxwai.nclientv3.settings.Global;
import com.maxwai.nclientv3.settings.Login;
import com.maxwai.nclientv3.utility.LogUtility;
import com.maxwai.nclientv3.utility.Utility;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SearchActivity extends GeneralActivity {
    public static final int CUSTOM_ID_START = 100000000;
    private static int customId = CUSTOM_ID_START;
    private final Ranges ranges = new Ranges();
    private final ArrayList<ChipTag> tags = new ArrayList<>();
    private final ArrayList<ChipTag> onlineChips = new ArrayList<>();
    private final ArrayList<ChipTag> onlineMergedChips = new ArrayList<>();
    private String onlineChipsIdentity;
    private long onlineChipsRevision = -1;
    private String onlineTagsLoadingIdentity;
    private final Runnable authObserver = () -> runOnUiThread(this::synchronizeOnlineSearchTags);
    private final Chip[] addChip = new Chip[TagType.values.length];
    private ChipGroup[] groups;
    private SearchView searchView;
    private AppCompatAutoCompleteTextView autoComplete;
    private TagType loadedTag = null;
    private HistoryAdapter adapter;
    private boolean advanced = false;
    private Ranges.TimeUnit temporaryUnit;
    private InputMethodManager inputMethodManager;
    private AlertDialog alertDialog;
    private final ExecutorService tagLoader = Executors.newSingleThreadExecutor();

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
        autoComplete = (AppCompatAutoCompleteTextView) getLayoutInflater().inflate(R.layout.autocomplete_entry, findViewById(R.id.appbar), false);
        autoComplete.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                alertDialog.dismiss();
                createChip();
                return true;
            }
            return false;
        });

        //init recyclerview
        recyclerView.setLayoutManager(new CustomLinearLayoutManager(this));
        recyclerView.setAdapter(adapter);
        recyclerView.addItemDecoration(new DividerItemDecoration(this, DividerItemDecoration.VERTICAL));


        searchView.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
            @Override
            public boolean onQueryTextSubmit(String query) {
                removeStaleOnlineTags();
                query = query.trim();
                if (query.isEmpty() && !advanced) return true;
                String submittedQuery = query;
                if (Login.wantsAccountTag() && !onlineTagsReady(onlineChipsIdentity, onlineChipsRevision,
                    Login.currentOnlineTagsIdentity(), Login.currentOnlineTagsRevision())) {
                    new MaterialAlertDialogBuilder(SearchActivity.this)
                        .setMessage(R.string.online_blacklist_search_unavailable)
                        .setPositiveButton(R.string.online_blacklist_refresh, (dialog, which) -> loadOnlineSearchTags())
                        .setNegativeButton(R.string.online_blacklist_search_without, (dialog, which) -> submitSearch(submittedQuery, true))
                        .setNeutralButton(R.string.cancel, null).show();
                    return true;
                }
                submitSearch(submittedQuery, false);
                return true;
            }

            @Override
            public boolean onQueryTextChange(String newText) {
                return false;
            }
        });

        populateGroup();
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
        removeStaleOnlineTags();
        boolean useAdvanced = withoutOnlineTags || advanced || (Login.wantsAccountTag()
            && (!onlineChips.isEmpty() || !onlineMergedChips.isEmpty()));
        if (!query.isEmpty()) adapter.addHistory(query);
        Intent intent = new Intent(this, MainActivity.class);
        intent.putExtra(getPackageName() + ".SEARCHMODE", true);
        intent.putExtra(getPackageName() + ".QUERY", query);
        intent.putExtra(getPackageName() + ".ADVANCED", useAdvanced);
        if (useAdvanced) {
            ArrayList<Tag> selected = new ArrayList<>(tags.size());
            for (ChipTag chip : tags)
                if ((!withoutOnlineTags || (!onlineChips.contains(chip) && !onlineMergedChips.contains(chip)))
                    && chip.getTag().getStatus() != TagStatus.DEFAULT) selected.add(chip.getTag());
            intent.putParcelableArrayListExtra(getPackageName() + ".TAGS", selected);
            intent.putExtra(getPackageName() + ".RANGES", ranges);
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
                    toPage.setText("");
                }
                advanced = true;
            }

            @Override
            public void neutral() {
                ranges.setFromPage(Ranges.UNDEFINED);
                fromPage.setText("");
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
                toPage.setText("");
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
                    button.setText(String.format(Locale.US, "%d %c", actual, Character.toUpperCase(temporaryUnit.getVal())));
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
                    button.setText("");
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
            button.setText("");
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
        for (ChipTag t : tags) {
            if (t.getTag().getType().equals(tag.getType())
                && (t.getTag().getId() == tag.getId() || t.getTag().getName().equals(tag.getName()))) return t;
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
            onlineChips.remove(c);
            onlineMergedChips.remove(c);
            advanced = true;
        });
        c.setOnClickListener(v -> {
            onlineChips.remove(c);
            onlineMergedChips.remove(c);
            c.updateStatus();
            advanced = true;
        });
        cg.addView(c);
        tags.add(c);
        return c;
    }

    private void removeStaleOnlineTags() {
        if (onlineChipsIdentity == null || (Objects.equals(onlineChipsIdentity, Login.currentOnlineTagsIdentity())
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

    private void loadDropdown(TagType type) {
        List<Tag> allTags = Queries.TagTable.getAllTagOfType(type);
        String[] tagNames = new String[allTags.size()];
        int i = 0;
        for (Tag t : allTags) tagNames[i++] = t.getName();
        autoComplete.setAdapter(new ArrayAdapter<>(SearchActivity.this, android.R.layout.simple_dropdown_item_1line, tagNames));
        loadedTag = type;
    }

    private void loadTag(TagType type) {
        if (type != loadedTag) loadDropdown(type);
        addDialog();
        autoComplete.requestFocus();
        inputMethodManager.showSoftInput(autoComplete, InputMethodManager.SHOW_IMPLICIT);
    }

    private ChipGroup getGroup(TagType type) {
        return groups[type.getId()];
    }

    private void addDialog() {
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this);
        builder.setView(autoComplete);
        autoComplete.setText("");
        builder.setPositiveButton(R.string.ok, (dialog, which) -> createChip());
        builder.setCancelable(true).setNegativeButton(R.string.cancel, null);
        builder.setTitle(R.string.insert_tag_name);
        try {
            alertDialog = builder.show();
        } catch (IllegalStateException e) {//the autoComplete is still attached to another View
            ((ViewGroup) autoComplete.getParent()).removeView(autoComplete);
            alertDialog = builder.show();
        }

    }

    private void createChip() {
        String name = autoComplete.getText().toString().toLowerCase(Locale.US);
        Tag tag = Queries.TagTable.searchTag(name, loadedTag);
        if (tag == null) tag = new Tag(name, 0, customId++, loadedTag, TagStatus.ACCEPTED);
        LogUtility.d("CREATED WITH ID: " + tag.getId());
        if (tagAlreadyExist(tag)) return;
        //remove add, insert new tag, reinsert add
        if (getGroup(loadedTag) != null) getGroup(loadedTag).removeView(addChip[loadedTag.getId()]);
        addChipTag(tag, true, true);
        getGroup(loadedTag).addView(addChip[loadedTag.getId()]);

        inputMethodManager.hideSoftInputFromWindow(searchView.getWindowToken(), InputMethodManager.HIDE_NOT_ALWAYS);
        autoComplete.setText("");
        advanced = true;
        if (autoComplete.getParent() != null)
            ((ViewGroup) autoComplete.getParent()).removeView(autoComplete);
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
            item.setIcon(isVisible ? R.drawable.ic_add : R.drawable.ic_close);
            Global.setTint(this, item.getIcon());
        }
        return super.onOptionsItemSelected(item);
    }


}
