// Modified for NReader by TheSkyC (2026); see NOTICE.
package com.maxwai.nclientv3.adapters;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.recyclerview.widget.RecyclerView;

import com.maxwai.nclientv3.R;
import com.maxwai.nclientv3.api.comments.Comment;
import com.maxwai.nclientv3.settings.Global;
import com.maxwai.nclientv3.settings.Login;
import com.maxwai.nclientv3.utility.ImageDownloadUtility;
import com.maxwai.nclientv3.utility.Utility;

import java.io.IOException;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Request;
import okhttp3.Response;

public class CommentAdapter extends RecyclerView.Adapter<CommentAdapter.ViewHolder> {
    private final List<Comment> comments;
    private final DateFormat format;
    private final int userId;
    private final AppCompatActivity context;
    private final boolean readOnly;

    public CommentAdapter(AppCompatActivity context, List<Comment> comments) {
        this(context, comments, false);
    }

    public CommentAdapter(AppCompatActivity context, List<Comment> comments, boolean readOnly) {
        this.context = context;
        this.readOnly = readOnly;
        format = android.text.format.DateFormat.getDateFormat(context);
        this.comments = comments == null ? new ArrayList<>() : comments;
        if (Login.isLogged() && Login.getUser() != null) {
            userId = Login.getUser().getId();
        } else userId = -1;
    }

    @NonNull
    @Override
    public CommentAdapter.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new ViewHolder(LayoutInflater.from(parent.getContext()).inflate(R.layout.comment_layout, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull CommentAdapter.ViewHolder holder, int pos) {
        int position = holder.getBindingAdapterPosition();
        Comment c = comments.get(position);
        holder.body.setMaxLines(7);
        holder.layout.setOnClickListener(v1 -> context.runOnUiThread(() -> holder.body.setMaxLines(holder.body.getMaxLines() == 7 ? 999 : 7)));
        holder.close.setVisibility(readOnly || c.getPosterId() != userId ? View.GONE : View.VISIBLE);
        holder.user.setText(c.getUsername());
        holder.body.setText(c.getComment());
        holder.date.setText(format.format(c.getPostDate()));
        holder.close.setOnClickListener(v -> {
            String submitUrl = String.format(Locale.US, Utility.getApiBaseUrl() + "comments/%d", c.getId());
            Global.getClient(context)
                .newCall(new Request.Builder().url(submitUrl).delete().build())
                .enqueue(new Callback() {
                    @Override
                    public void onFailure(@NonNull Call call, @NonNull IOException e) {

                    }

                    @Override
                    public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                        if (response.body().string().contains("true")) {
                            comments.remove(position);
                            context.runOnUiThread(() -> notifyItemRemoved(position));
                        }
                    }
                });
        });
        boolean useDefaultAvatar = c.getAvatarUrl() == null || Global.getDownloadPolicy() != Global.DataUsageType.FULL;
        holder.userImage.setScaleType(useDefaultAvatar ? ImageButton.ScaleType.FIT_CENTER : ImageButton.ScaleType.CENTER_CROP);
        holder.userImage.setPadding(useDefaultAvatar ? dp(4) : 0, useDefaultAvatar ? dp(4) : 0,
            useDefaultAvatar ? dp(4) : 0, useDefaultAvatar ? dp(4) : 0);
        if (useDefaultAvatar)
            ImageDownloadUtility.loadImage(R.drawable.ic_person, holder.userImage);
        else
            ImageDownloadUtility.loadAvatarImage(context, c.getAvatarUrl(), holder.userImage);
    }

    private int dp(int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    @Override
    public int getItemCount() {
        return comments.size();
    }

    public void addComment(Comment c) {
        comments.add(0, c);
        context.runOnUiThread(() -> notifyItemInserted(0));
    }

    public void addComments(List<Comment> additional) {
        int start = comments.size();
        comments.addAll(additional);
        notifyItemRangeInserted(start, additional.size());
    }

    public List<Comment> getComments() {
        return new ArrayList<>(comments);
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {
        final ImageButton userImage;
        final ImageButton close;
        final TextView user;
        final TextView body;
        final TextView date;
        final ConstraintLayout layout;

        public ViewHolder(@NonNull View v) {
            super(v);
            layout = v.findViewById(R.id.master_layout);
            userImage = v.findViewById(R.id.propic);
            close = v.findViewById(R.id.close);
            user = v.findViewById(R.id.username);
            body = v.findViewById(R.id.body);
            date = v.findViewById(R.id.date);
        }
    }
}
