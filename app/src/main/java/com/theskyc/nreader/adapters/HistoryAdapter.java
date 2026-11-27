package com.theskyc.nreader.adapters;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.theskyc.nreader.R;
import com.theskyc.nreader.SearchActivity;
import com.theskyc.nreader.components.classes.History;
import com.theskyc.nreader.settings.Global;

import java.util.HashSet;
import java.util.List;

public class HistoryAdapter extends RecyclerView.Adapter<HistoryAdapter.ViewHolder> {
    private final List<History> history;
    private final SearchActivity context;

    public HistoryAdapter(SearchActivity context) {
        this.context = context;
        if (!Global.isKeepHistory())
            context.getSharedPreferences("History", 0).edit().clear().apply();
        history = Global.isKeepHistory() ? History.setToList(context.getSharedPreferences("History", 0).getStringSet("history", new HashSet<>())) : null;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new ViewHolder(LayoutInflater.from(parent.getContext()).inflate(R.layout.entry_history, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        History historyEntry = history.get(position);
        String entry = historyEntry.getValue();
        holder.text.setText(entry);
        holder.master.setOnClickListener(v -> context.setQuery(entry, true));
        holder.imageButton.setOnClickListener(v -> new MaterialAlertDialogBuilder(context)
            .setTitle(R.string.options)
            .setItems(new String[]{context.getString(R.string.edit), context.getString(R.string.delete)}, (dialog, which) -> {
                if (which == 0) context.setQuery(entry, false);
                else if (which == 1) removeHistory(historyEntry);
            })
            .show());
    }

    @Override
    public int getItemCount() {
        return history == null ? 0 : history.size();
    }

    public void addHistory(String value) {
        if (!Global.isKeepHistory()) return;
        History history = new History(value, false);
        int pos = this.history.indexOf(history);
        if (pos >= 0) this.history.set(pos, history);
        else this.history.add(history);
        context.getSharedPreferences("History", 0).edit().putStringSet("history", History.listToSet(this.history)).apply();
    }

    public void removeHistory(int pos) {
        if (pos < 0 || pos >= history.size()) return;
        history.remove(pos);
        context.getSharedPreferences("History", 0).edit().putStringSet("history", History.listToSet(this.history)).apply();
        context.runOnUiThread(() -> notifyItemRemoved(pos));
    }

    private void removeHistory(History entry) {
        for (int i = 0; i < history.size(); i++) {
            if (history.get(i) == entry) {
                removeHistory(i);
                return;
            }
        }
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {
        final ConstraintLayout master;
        final TextView text;
        final ImageButton imageButton;

        public ViewHolder(@NonNull View itemView) {
            super(itemView);
            this.master = itemView.findViewById(R.id.master_layout);
            this.text = itemView.findViewById(R.id.text);
            this.imageButton = itemView.findViewById(R.id.edit);
        }
    }
}
