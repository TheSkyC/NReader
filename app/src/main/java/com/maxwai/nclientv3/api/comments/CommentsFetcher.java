// Modified for NReader by TheSkyC (2026); see NOTICE.
package com.maxwai.nclientv3.api.comments;

import android.content.Context;
import android.util.JsonReader;
import android.util.JsonToken;

import com.maxwai.nclientv3.CommentActivity;
import com.maxwai.nclientv3.adapters.CommentAdapter;
import com.maxwai.nclientv3.settings.Global;
import com.maxwai.nclientv3.utility.LogUtility;
import com.maxwai.nclientv3.utility.Utility;

import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

public class CommentsFetcher extends Thread {
    private static final String COMMENT_API_URL = Utility.getApiBaseUrl() + "galleries/%d/comments";
    private final int id;
    private final CommentActivity commentActivity;
    private final List<Comment> comments = new ArrayList<>();

    public interface Callback {
        void onResult(List<Comment> comments, int pages, boolean success);
    }

    public static void fetch(Context context, int id, int page, Callback callback) {
        Context application = context.getApplicationContext();
        new Thread(() -> {
            List<Comment> result = new ArrayList<>();
            boolean success = false;
            int pages = 0;
            String url = String.format(Locale.US, COMMENT_API_URL + "?page=%d&per_page=50", id, page);
            try (Response response = Global.getClient(application)
                .newCall(new Request.Builder().url(url).build()).execute()) {
                ResponseBody body = response.body();
                if (!response.isSuccessful() || body == null) throw new IOException("Comment request failed");
                try (JsonReader reader = new JsonReader(body.charStream())) {
                    pages = readPage(reader, result);
                }
                success = true;
            } catch (IOException | RuntimeException e) {
                LogUtility.w("Failed to load comments", e);
            }
            callback.onResult(result, pages, success);
        }).start();
    }

    static int readPage(JsonReader reader, List<Comment> result) throws IOException {
        int pages = -1;
        boolean hasResult = false;
        reader.beginObject();
        while (reader.hasNext()) {
            switch (reader.nextName()) {
                case "result":
                    reader.beginArray();
                    while (reader.hasNext()) result.add(new Comment(reader));
                    reader.endArray();
                    hasResult = true;
                    break;
                case "num_pages":
                    pages = reader.nextInt();
                    break;
                default:
                    reader.skipValue();
            }
        }
        reader.endObject();
        if (!hasResult || pages < 0 || pages > 2000) throw new IOException("Invalid comment page");
        return pages;
    }

    public CommentsFetcher(CommentActivity commentActivity, int id) {
        this.id = id;
        this.commentActivity = commentActivity;
    }

    @Override
    public void run() {
        populateComments();
        postResult();
    }

    private void postResult() {
        CommentAdapter commentAdapter = new CommentAdapter(commentActivity, comments);
        commentActivity.setAdapter(commentAdapter);
        commentActivity.runOnUiThread(() -> {
            commentActivity.getRecycler().setAdapter(commentAdapter);
            commentActivity.getRefresher().setRefreshing(false);
        });
    }

    private void populateComments() {
        String url = String.format(Locale.US, COMMENT_API_URL, id);
        try (Response response = Objects.requireNonNull(Global.getClient()).newCall(new Request.Builder().url(url).build()).execute()) {

            ResponseBody body = response.body();
            try (JsonReader reader = new JsonReader(new InputStreamReader(body.byteStream()))) {
                if(reader.peek() == JsonToken.BEGIN_ARRAY) {
                    reader.beginArray();
                    while (reader.hasNext())
                        comments.add(new Comment(reader));
                }
            }
        } catch (NullPointerException | IOException e) {
            LogUtility.w("Error getting comments", e);
        }
    }
}
