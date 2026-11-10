// NReader modifications by TheSkyC (2026); see NOTICE.
package com.maxwai.nclientv3.adapters;

import android.content.ContentValues;
import android.content.Intent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

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
    private final List<ContentValues> plans;

    public SearchPlanAdapter(BookmarkActivity activity) {
        this.activity = activity;
        plans = new ArrayList<>();
        SearchPlanStore.execute(() -> {
            try {
                List<ContentValues> loaded = SearchPlanStore.list();
                activity.runOnUiThread(() -> {
                    if (activity.isFinishing() || activity.isDestroyed()) return;
                    plans.clear();
                    plans.addAll(loaded);
                    notifyDataSetChanged();
                });
            } catch (RuntimeException exception) {
                LogUtility.e("Unable to load search plans", exception);
                showStorageError();
            }
        });
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new Holder(LayoutInflater.from(parent.getContext()).inflate(R.layout.bookmark_layout, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        ContentValues plan = plans.get(position);
        holder.title.setText(plan.getAsString("name"));
        holder.page.setText(activity.getString(R.string.bookmark_page_format, plan.getAsInteger("page")));
        holder.remove.setContentDescription(activity.getString(R.string.delete_search_plan));
        holder.remove.setEnabled(true);
        holder.remove.setOnClickListener(view -> {
            int current = holder.getBindingAdapterPosition();
            if (current == RecyclerView.NO_POSITION) return;
            long id = plans.get(current).getAsLong("id");
            view.setEnabled(false);
            SearchPlanStore.execute(() -> {
                try {
                    boolean deleted = SearchPlanStore.delete(id);
                    activity.runOnUiThread(() -> {
                        if (activity.isFinishing() || activity.isDestroyed()) return;
                        for (int index = 0; index < plans.size(); index++) {
                            if (plans.get(index).getAsLong("id") == id) {
                                if (deleted) {
                                    plans.remove(index);
                                    notifyItemRemoved(index);
                                } else notifyItemChanged(index);
                                break;
                            }
                        }
                    });
                } catch (RuntimeException exception) {
                    LogUtility.e("Unable to delete search plan", exception);
                    activity.runOnUiThread(() -> {
                        if (!activity.isFinishing() && !activity.isDestroyed()) notifyDataSetChanged();
                    });
                    showStorageError();
                }
            });
        });
        holder.itemView.findViewById(R.id.master_layout).setOnClickListener(view -> {
            String sort = plan.getAsString("sort");
            InspectorV3 inspector = InspectorV3.savedSearchInspector(activity, plan.getAsString("query"),
                plan.getAsInteger("page"), SortType.findFromAddition(sort.isEmpty() ? null : sort), null);
            Intent intent = new Intent(activity, MainActivity.class);
            intent.putExtra(activity.getPackageName() + ".BYBOOKMARK", true);
            intent.putExtra(activity.getPackageName() + ".INSPECTOR", inspector);
            IntentUtility.startAnotherActivity(activity, intent);
        });
    }

    private void showStorageError() {
        activity.runOnUiThread(() -> {
            if (!activity.isFinishing() && !activity.isDestroyed())
                Toast.makeText(activity, R.string.search_plan_storage_error, Toast.LENGTH_LONG).show();
        });
    }

    @Override
    public int getItemCount() {
        return plans.size();
    }

    static class Holder extends RecyclerView.ViewHolder {
        final TextView title;
        final TextView page;
        final View remove;

        Holder(View view) {
            super(view);
            title = view.findViewById(R.id.title);
            page = view.findViewById(R.id.page);
            remove = view.findViewById(R.id.remove_button);
        }
    }
}
