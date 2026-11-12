package com.maxwai.nclientv3.loginapi;

import android.content.Context;

import androidx.annotation.NonNull;

import com.maxwai.nclientv3.api.components.Tag;
import com.maxwai.nclientv3.api.enums.TagStatus;
import com.maxwai.nclientv3.api.enums.TagType;
import com.maxwai.nclientv3.settings.ApiAuthInterceptor;
import com.maxwai.nclientv3.settings.AuthStore;
import com.maxwai.nclientv3.settings.Global;
import com.maxwai.nclientv3.settings.Login;
import com.maxwai.nclientv3.utility.LogUtility;
import com.maxwai.nclientv3.utility.Utility;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

public class LoadTags extends Thread {
    private static final long MAX_RESPONSE_BYTES = 2L * 1024 * 1024;
    private final Context context;
    private final Completion completion;

    public interface Completion {
        void onComplete(boolean successful);
    }

    public LoadTags(@NonNull Context context) {
        this(context, null);
    }

    public LoadTags(@NonNull Context context, Completion completion) {
        this.context = context.getApplicationContext();
        this.completion = completion;
    }

    static List<Tag> parse(@NonNull String json) throws IOException {
        try {
            JSONObject object = new JSONObject(json);
            JSONArray items = object.getJSONArray("tags");
            if (object.getInt("count") != items.length() || items.length() > 10000)
                throw new IOException("Invalid blacklist count");
            List<Tag> tags = new ArrayList<>();
            Set<Integer> ids = new HashSet<>();
            for (int index = 0; index < items.length(); index++) {
                JSONObject item = items.getJSONObject(index);
                int id = item.getInt("id");
                int count = item.getInt("count");
                String name = item.getString("name");
                TagType type = TagType.typeByName(item.getString("type"));
                if (id <= 0 || count < 0 || name.trim().isEmpty() || name.length() > 512
                    || name.indexOf('"') >= 0 || name.indexOf('\\') >= 0
                    || hasControlCharacters(name)
                    || type == TagType.UNKNOWN || !ids.add(id))
                    throw new IOException("Invalid blacklisted tag");
                tags.add(new Tag(name, count, id, type, TagStatus.DEFAULT));
            }
            return tags;
        } catch (JSONException | IllegalArgumentException exception) {
            throw new IOException("Invalid blacklist response", exception);
        }
    }

    private static boolean hasControlCharacters(String value) {
        for (int index = 0; index < value.length(); index++)
            if (Character.isISOControl(value.charAt(index))) return true;
        return false;
    }

    @Override
    public void run() {
        String identity = AuthStore.activeIdentity(context);
        if (identity == null) {
            if (completion != null) completion.onComplete(false);
            return;
        }
        long revision = Login.beginOnlineTagsLoad(context, identity);
        if (revision < 0) {
            if (completion != null) completion.onComplete(false);
            return;
        }
        boolean successful = false;
        try {
            Request request = new Request.Builder().url(Utility.getAuthenticatedApiBaseUrl(context) + "blacklist")
                .tag(ApiAuthInterceptor.ExpectedIdentity.class, new ApiAuthInterceptor.ExpectedIdentity(identity)).build();
            try (Response response = Global.getClient(context).newCall(request).execute()) {
                ResponseBody body = response.body();
                if (!response.isSuccessful() || body == null) throw new IOException("Blacklist request failed");
                if (body.contentLength() > MAX_RESPONSE_BYTES || body.source().request(MAX_RESPONSE_BYTES + 1))
                    throw new IOException("Blacklist response too large");
                List<Tag> tags = parse(body.source().readUtf8());
                successful = Login.replaceOnlineTags(context, identity, revision, tags);
            }
        } catch (IOException | RuntimeException exception) {
            LogUtility.w("Unable to load online blacklist", exception);
        } finally {
            if (completion != null) completion.onComplete(successful);
        }
    }
}
