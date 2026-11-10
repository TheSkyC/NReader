// NReader modifications by TheSkyC (2026); see NOTICE.
package com.maxwai.nclientv3.adapters;

import android.content.ContentValues;
import android.content.Intent;
import android.net.Uri;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.maxwai.nclientv3.BookmarkActivity;
import com.maxwai.nclientv3.MainActivity;
import com.maxwai.nclientv3.R;
import com.maxwai.nclientv3.api.InspectorV3;
import com.maxwai.nclientv3.api.enums.SortType;
import com.maxwai.nclientv3.async.database.SearchPlanStore;
import com.maxwai.nclientv3.utility.IntentUtility;
import com.maxwai.nclientv3.utility.LogUtility;

import java.util.ArrayList;
import java.util.List;

public class SearchPlanAdapter extends RecyclerView.Adapter<SearchPlanAdapter.Holder> {
    private final BookmarkActivity activity;
    private final List<ContentValues> plans = new ArrayList<>();

    public SearchPlanAdapter(BookmarkActivity activity) {
        this.activity = activity;
        reload();
    }

    private void reload() {
        SearchPlanStore.execute(() -> {
            try {
                List<ContentValues> loaded = SearchPlanStore.list();
                activity.runOnUiThread(() -> {
                    if (!active()) return;
                    plans.clear();
                    plans.addAll(loaded);
                    notifyDataSetChanged();
                });
            } catch (RuntimeException exception) {
                reportError(exception);
            }
        });
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new Holder(LayoutInflater.from(parent.getContext()).inflate(R.layout.search_plan_row, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        ContentValues plan = plans.get(position);
        String name = plan.getAsString("name");
        holder.title.setText(plan.getAsInteger("pinned") == 1
            ? activity.getString(R.string.search_plan_pinned_format, name) : name);
        holder.page.setText(activity.getString(R.string.bookmark_page_format, plan.getAsInteger("page")));
        holder.actions.setOnClickListener(view -> showActions(plan));
        holder.itemView.setOnClickListener(view -> open(plan));
    }

    private void open(ContentValues plan) {
        String sort = plan.getAsString("sort");
        InspectorV3 inspector = InspectorV3.savedSearchInspector(activity, plan.getAsString("query"),
            plan.getAsInteger("page"), SortType.findFromAddition(sort.isEmpty() ? null : sort), null);
        Intent intent = new Intent(activity, MainActivity.class);
        intent.putExtra(activity.getPackageName() + ".BYBOOKMARK", true);
        intent.putExtra(activity.getPackageName() + ".INSPECTOR", inspector);
        IntentUtility.startAnotherActivity(activity, intent);
    }

    private void showActions(ContentValues plan) {
        boolean pinned = plan.getAsInteger("pinned") == 1;
        String[] actions = {activity.getString(R.string.search_plan_edit), activity.getString(R.string.search_plan_copy),
            activity.getString(pinned ? R.string.search_plan_unpin : R.string.search_plan_pin),
            activity.getString(R.string.search_plan_share), activity.getString(R.string.delete_search_plan)};
        new MaterialAlertDialogBuilder(activity).setTitle(plan.getAsString("name"))
            .setItems(actions, (dialog, choice) -> {
                if (choice == 0) edit(plan);
                else if (choice == 1) {
                    ContentValues copy = new ContentValues(plan);
                    copy.remove("id");
                    copy.put("pinned", 0);
                    mutate(() -> SearchPlanStore.insert(copy) > 0);
                } else if (choice == 2) {
                    ContentValues changed = new ContentValues(plan);
                    long id = changed.getAsLong("id");
                    changed.remove("id");
                    changed.put("pinned", pinned ? 0 : 1);
                    mutate(() -> SearchPlanStore.update(id, changed));
                } else if (choice == 3) share(plan);
                else if (choice == 4) {
                    new MaterialAlertDialogBuilder(activity).setMessage(R.string.delete_search_plan_confirm)
                        .setNegativeButton(android.R.string.cancel, null)
                        .setPositiveButton(R.string.delete_search_plan, (ignored, which) ->
                            mutate(() -> SearchPlanStore.delete(plan.getAsLong("id")))).show();
                }
            }).show();
    }

    private void edit(ContentValues plan) {
        View form = LayoutInflater.from(activity).inflate(R.layout.search_plan_editor, null);
        EditText name = form.findViewById(R.id.plan_name);
        EditText query = form.findViewById(R.id.plan_query);
        EditText page = form.findViewById(R.id.plan_page);
        Spinner sort = form.findViewById(R.id.plan_sort);
        SortType[] types = SortType.values();
        List<String> labels = new ArrayList<>();
        for (SortType type : types) labels.add(activity.getString(type.getNameId()));
        sort.setAdapter(new ArrayAdapter<>(activity, android.R.layout.simple_spinner_dropdown_item, labels));
        sort.setSelection(SortType.findFromAddition(plan.getAsString("sort")).ordinal());
        name.setText(plan.getAsString("name"));
        query.setText(plan.getAsString("query"));
        page.setText(plan.getAsString("page"));
        AlertDialog dialog = new MaterialAlertDialogBuilder(activity).setTitle(R.string.search_plan_edit)
            .setView(form).setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok, null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            int pageNumber;
            try {
                pageNumber = Integer.parseInt(page.getText().toString());
                if (pageNumber < 1) throw new NumberFormatException();
            } catch (NumberFormatException exception) {
                page.setError(activity.getString(R.string.search_plan_invalid_page));
                return;
            }
            ContentValues changed = new ContentValues(plan);
            long id = changed.getAsLong("id");
            changed.remove("id");
            changed.put("name", name.getText().toString().trim());
            changed.put("query", query.getText().toString());
            String addition = types[sort.getSelectedItemPosition()].getUrlAddition();
            changed.put("sort", addition == null ? "" : addition);
            changed.put("page", pageNumber);
            if (!SearchPlanStore.isValid(changed, false)) {
                name.setError(activity.getString(R.string.search_plan_invalid));
                return;
            }
            view.setEnabled(false);
            SearchPlanStore.execute(() -> {
                try {
                    if (!SearchPlanStore.update(id, changed))
                        throw new IllegalStateException("Search plan no longer exists");
                    activity.runOnUiThread(() -> {
                        if (active()) dialog.dismiss();
                    });
                    reload();
                } catch (RuntimeException exception) {
                    activity.runOnUiThread(() -> {
                        if (active()) view.setEnabled(true);
                    });
                    reportError(exception);
                }
            });
        }));
        dialog.show();
    }

    private void share(ContentValues plan) {
        Uri.Builder link = new Uri.Builder().scheme("https").authority("nhentai.net").path("/search/")
            .appendQueryParameter("q", plan.getAsString("query"))
            .appendQueryParameter("page", plan.getAsString("page"));
        String sort = plan.getAsString("sort");
        if (!sort.isEmpty()) link.appendQueryParameter("sort", sort);
        Intent intent = new Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, link.build().toString());
        activity.startActivity(Intent.createChooser(intent, activity.getString(R.string.search_plan_share)));
    }

    private interface Mutation { boolean run(); }

    private void mutate(Mutation mutation) {
        SearchPlanStore.execute(() -> {
            try {
                if (!mutation.run()) throw new IllegalStateException("Search plan no longer exists");
                reload();
            } catch (RuntimeException exception) {
                reportError(exception);
            }
        });
    }

    private boolean active() {
        return !activity.isFinishing() && !activity.isDestroyed();
    }

    private void reportError(RuntimeException exception) {
        LogUtility.e("Unable to update search plans", exception);
        activity.runOnUiThread(() -> {
            if (active()) Toast.makeText(activity, R.string.search_plan_storage_error, Toast.LENGTH_LONG).show();
        });
    }

    @Override
    public int getItemCount() { return plans.size(); }

    static class Holder extends RecyclerView.ViewHolder {
        final TextView title;
        final TextView page;
        final View actions;

        Holder(View view) {
            super(view);
            title = view.findViewById(R.id.title);
            page = view.findViewById(R.id.page);
            actions = view.findViewById(R.id.plan_actions);
        }
    }
}
