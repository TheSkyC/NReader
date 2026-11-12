// Modified for NReader by TheSkyC (2026); see NOTICE.
package com.maxwai.nclientv3.adapters;

import android.database.Cursor;
import android.content.SharedPreferences;
import android.util.JsonWriter;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.maxwai.nclientv3.R;
import com.maxwai.nclientv3.TagFilterActivity;
import com.maxwai.nclientv3.api.components.Tag;
import com.maxwai.nclientv3.api.enums.TagStatus;
import com.maxwai.nclientv3.api.enums.TagType;
import com.maxwai.nclientv3.async.database.Queries;
import com.maxwai.nclientv3.async.database.AsyncCursorQuery;
import com.maxwai.nclientv3.loginapi.LoadTags;
import com.maxwai.nclientv3.settings.ApiAuthInterceptor;
import com.maxwai.nclientv3.settings.AuthStore;
import com.maxwai.nclientv3.settings.Global;
import com.maxwai.nclientv3.settings.Login;
import com.maxwai.nclientv3.settings.TagV2;
import com.maxwai.nclientv3.utility.ImageDownloadUtility;
import com.maxwai.nclientv3.utility.Utility;

import java.io.IOException;
import java.io.StringWriter;
import java.util.Locale;
import java.util.Objects;

import org.json.JSONException;
import org.json.JSONObject;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class TagsAdapter extends RecyclerView.Adapter<TagsAdapter.ViewHolder> {
    @NonNull
    private final TagFilterActivity context;
    private final boolean logged = Login.isLogged();
    private final String identity;
    private volatile boolean closed;
    private final Runnable authObserver;
    private final TagType type;
    private final TagMode tagMode;
    private String lastQuery = null;
    private Cursor cursor = null;
    private final AsyncCursorQuery queryLoader;

    public TagsAdapter(@NonNull TagFilterActivity cont, String query, boolean online) {
        this(cont, query, online, new AsyncCursorQuery());
    }

    TagsAdapter(@NonNull TagFilterActivity cont, String query, boolean online, AsyncCursorQuery queryLoader) {
        this.context = cont;
        this.queryLoader = queryLoader;
        this.identity = AuthStore.activeIdentity(cont);
        this.authObserver = createAuthObserver();
        this.type = null;
        this.tagMode = online ? TagMode.ONLINE : TagMode.OFFLINE;
        AuthStore.observeIdentityChanges(authObserver);
        filter(query);
    }

    public TagsAdapter(@NonNull TagFilterActivity cont, String query, TagType type) {
        this.context = cont;
        this.queryLoader = new AsyncCursorQuery();
        this.identity = AuthStore.activeIdentity(cont);
        this.authObserver = createAuthObserver();
        this.type = type;
        this.tagMode = TagMode.TYPE;
        AuthStore.observeIdentityChanges(authObserver);
        filter(query);
    }

    public void filter(String query) {
        lastQuery = query == null ? "" : query;
        String requestedQuery = lastQuery;
        boolean sortedByName = TagV2.isSortedByName();
        long requestedRevision = tagMode == TagMode.ONLINE ? Login.currentOnlineTagsRevision() : -1;
        queryLoader.load(() -> Queries.TagTable.getFilterCursor(requestedQuery, type, tagMode == TagMode.ONLINE, sortedByName), result -> {
            if (tagMode == TagMode.ONLINE && (!Objects.equals(identity, AuthStore.activeIdentity(context))
                || requestedRevision < 0 || requestedRevision != Login.currentOnlineTagsRevision())) {
                cursor = null;
                notifyDataSetChanged();
                return;
            }
            int oldCount = getItemCount(), newCount = result.getCount();
            cursor = result;
            if (newCount > oldCount) notifyItemRangeInserted(oldCount, newCount - oldCount);
            else if (oldCount > newCount) notifyItemRangeRemoved(newCount, oldCount - newCount);
            notifyItemRangeChanged(0, Math.min(newCount, oldCount));
        }, () -> {});
    }

    public void close() {
        closed = true;
        AuthStore.removeIdentityObserver(authObserver);
        cursor = null;
        queryLoader.close();
    }


    private Runnable createAuthObserver() {
        return () -> {
            if (tagMode == TagMode.ONLINE && !Objects.equals(identity, AuthStore.activeIdentity(context))) context.runOnUiThread(() -> {
                if (closed) return;
                cursor = null;
                notifyDataSetChanged();
            });
        };
    }

    @NonNull
    @Override
    public TagsAdapter.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new TagsAdapter.ViewHolder(LayoutInflater.from(parent.getContext()).inflate(R.layout.entry_tag_layout, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull final TagsAdapter.ViewHolder holder, int position) {
        cursor.moveToPosition(position);
        final Tag ent = Queries.TagTable.cursorToTag(cursor);
        holder.title.setText(ent.getName());
        holder.count.setText(String.format(Locale.US, "%d", ent.getCount()));
        holder.master.setOnClickListener(v -> {
            switch (tagMode) {
                case OFFLINE:
                case TYPE:
                    if (TagV2.maxTagReached() && ent.getStatus() == TagStatus.DEFAULT) {
                        context.runOnUiThread(() -> Toast.makeText(context, context.getString(R.string.tags_max_reached, TagV2.MAXTAGS), Toast.LENGTH_LONG).show());
                    } else {
                        TagV2.updateStatus(ent);
                        updateLogo(holder.imgView, ent.getStatus());
                    }
                    break;
                case ONLINE:
                    onlineTagUpdate(ent, false);
                    break;
            }

        });
        if (tagMode != TagMode.ONLINE && logged) holder.master.setOnLongClickListener(view -> {
            if (!Login.isOnlineTags(ent)) showBlacklistDialog(ent);
            else
                Toast.makeText(context, R.string.tag_already_in_blacklist, Toast.LENGTH_SHORT).show();
            return true;
        });
        updateLogo(holder.imgView, tagMode == TagMode.ONLINE ? TagStatus.AVOIDED : ent.getStatus());
    }

    @Override
    public int getItemCount() {
        return cursor == null ? 0 : cursor.getCount();
    }

    private void showBlacklistDialog(final Tag tag) {
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(context);
        builder.setIcon(R.drawable.ic_star_border).setTitle(R.string.add_to_online_blacklist).setMessage(R.string.are_you_sure);
        builder.setPositiveButton(R.string.yes, (dialogInterface, i) -> {
            onlineTagUpdate(tag, true);
        }).setNegativeButton(R.string.no, null).show();
    }

    private void onlineTagUpdate(final Tag tag, final boolean add) {
        long operation = identity == null ? -1 : Login.beginOnlineTagMutation(context, identity);
        if (operation < 0) {
            Toast.makeText(context, R.string.online_blacklist_update_error, Toast.LENGTH_SHORT).show();
            return;
        }
        try {
        StringWriter sw = new StringWriter();
        JsonWriter jw = new JsonWriter(sw);
        jw.beginObject().name("added").beginArray();
        if (add) jw.value(tag.getId());
        jw.endArray().name("removed").beginArray();
        if (!add) jw.value(tag.getId());
        jw.endArray().endObject();

        final String url = Utility.getAuthenticatedApiBaseUrl(context) + "blacklist";
        final RequestBody ss = RequestBody.create(sw.toString(), MediaType.get("application/json"));
        Global.getClient(context).newBuilder().retryOnConnectionFailure(false)
            .followRedirects(false).followSslRedirects(false).build()
            .newCall(new Request.Builder().url(url).post(ss)
                .tag(ApiAuthInterceptor.ExpectedIdentity.class, new ApiAuthInterceptor.ExpectedIdentity(identity,
                    () -> Login.isOnlineTagMutationCurrent(context, identity, operation))).build())
            .enqueue(new Callback() {
                @Override
                public void onFailure(@NonNull Call call, @NonNull IOException e) {
                    failedOnlineTagUpdate(operation);
                }

                @Override
                public void onResponse(@NonNull Call call, @NonNull Response response) {
                    try (response) {
                        if (!response.isSuccessful() || response.body() == null
                            || response.body().contentLength() > 65536
                            || response.body().source().request(65537)) throw new IOException("Blacklist update failed");
                        JSONObject result = new JSONObject(response.body().source().readUtf8());
                        if (!(result.opt("success") instanceof Boolean) || !result.getBoolean("success")
                            || result.getInt("count") < 0)
                            throw new IOException("Blacklist update rejected");
                        if (!Login.finishOnlineTagMutation(context, identity, operation, tag, add)) return;
                        context.runOnUiThread(() -> {
                            if (!closed && !context.isFinishing() && !context.isDestroyed()) filter(lastQuery);
                        });
                    } catch (IOException | JSONException | RuntimeException exception) {
                        failedOnlineTagUpdate(operation);
                    }
                }
            });
        } catch (IOException | RuntimeException exception) {
            failedOnlineTagUpdate(operation);
        }
    }

    private void failedOnlineTagUpdate(long operation) {
        if (!Login.invalidateOnlineTags(identity, operation)) return;
        if (identity.equals(AuthStore.activeIdentity(context)))
            new LoadTags(context, successful -> context.runOnUiThread(() -> {
                if (!closed && !context.isFinishing() && !context.isDestroyed()) {
                    filter(lastQuery);
                }
            })).start();
        context.runOnUiThread(() -> {
            if (!closed && !context.isFinishing() && !context.isDestroyed()) {
                Toast.makeText(context, R.string.online_blacklist_update_error, Toast.LENGTH_SHORT).show();
                filter(lastQuery);
            }
        });
    }

    private void updateLogo(ImageView img, TagStatus s) {
        context.runOnUiThread(() -> {
            switch (s) {
                case DEFAULT:
                    img.setImageDrawable(null);
                    break;//ImageDownloadUtility.loadImage(R.drawable.ic_void,img); break;
                case ACCEPTED:
                    ImageDownloadUtility.loadImage(R.drawable.ic_check, img);
                    Global.setTint(context, img.getDrawable());
                    break;
                case AVOIDED:
                    ImageDownloadUtility.loadImage(R.drawable.ic_close, img);
                    Global.setTint(context, img.getDrawable());
                    break;
            }
        });

    }

    public void addItem() {
        filter(lastQuery);
    }

    private enum TagMode {ONLINE, OFFLINE, TYPE}

    public static class ViewHolder extends RecyclerView.ViewHolder {
        final ImageView imgView;
        final TextView title, count;
        final ConstraintLayout master;

        ViewHolder(View v) {
            super(v);
            imgView = v.findViewById(R.id.image);
            title = v.findViewById(R.id.title);
            count = v.findViewById(R.id.count);
            master = v.findViewById(R.id.master_layout);
        }
    }


}
