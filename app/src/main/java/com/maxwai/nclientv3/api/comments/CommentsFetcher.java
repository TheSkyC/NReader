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
import okhttp3.OkHttpClient;
import okhttp3.Call;
import okhttp3.Response;
import okhttp3.ResponseBody;

public class CommentsFetcher extends Thread {
    private static final int MAX_PAGE = 2000;
    private static final int COMMENTS_PER_PAGE = 50;
    private static final long MAX_RESPONSE_BYTES = 2L * 1024 * 1024;
    private final int id;
    private final CommentActivity commentActivity;
    private final List<Comment> comments = new ArrayList<>();

    public interface Callback {
        void onResult(List<Comment> comments, int pages, boolean success);
    }

    public static final class RequestHandle {
        private Callback callback;
        private Call call;
        private boolean canceled;

        private RequestHandle(Callback callback) {
            this.callback = callback;
        }

        public void cancel() {
            Call active;
            synchronized (this) {
                canceled = true;
                callback = null;
                active = call;
            }
            if (active != null) active.cancel();
        }

        private synchronized boolean attach(Call active) {
            if (canceled) {
                active.cancel();
                return false;
            }
            call = active;
            return true;
        }

        private synchronized boolean isCanceled() {
            return canceled;
        }

        private synchronized void complete(List<Comment> result, int pages, boolean success) {
            call = null;
            Callback recipient = callback;
            callback = null;
            if (recipient != null) recipient.onResult(result, pages, success);
        }
    }

    public static RequestHandle fetch(Context context, int id, int page, Callback callback) {
        Context application = context.getApplicationContext();
        return fetch(application, id, page, null, callback);
    }

    static RequestHandle fetch(Context context, int id, int page, OkHttpClient client, Callback callback) {
        RequestHandle handle = new RequestHandle(callback);
        new Thread(() -> {
            List<Comment> result = new ArrayList<>();
            boolean success = false;
            int pages = 0;
            try {
                Call call = (client == null ? Global.getClient(context) : client).newCall(createRequest(id, page));
                if (!handle.attach(call)) return;
                try (Response response = call.execute()) {
                    ResponseBody body = response.body();
                    if (!response.isSuccessful() || body == null) throw new IOException("Comment request failed");
                    if (body.contentLength() > MAX_RESPONSE_BYTES || body.source().request(MAX_RESPONSE_BYTES + 1))
                        throw new IOException("Comment response too large");
                    try (JsonReader reader = new JsonReader(body.charStream())) {
                        pages = readPage(reader, result);
                    }
                    success = true;
                }
            } catch (IOException | RuntimeException e) {
                if (!handle.isCanceled()) LogUtility.w("Failed to load comments", e);
                result.clear();
            }
            handle.complete(result, pages, success);
        }).start();
        return handle;
    }

    static Request createRequest(int id, int page) {
        if (id <= 0 || page <= 0 || page > MAX_PAGE) throw new IllegalArgumentException("Invalid comment request");
        String url = String.format(Locale.US, Utility.getApiBaseUrl() + "galleries/%d/comments?page=%d&per_page=%d",
            id, page, COMMENTS_PER_PAGE);
        return new Request.Builder().url(url).build();
    }

    static int readPage(JsonReader reader, List<Comment> result) throws IOException {
        int pages = -1;
        boolean hasResult = false;
        reader.beginObject();
        while (reader.hasNext()) {
            switch (reader.nextName()) {
                case "result":
                    reader.beginArray();
                    while (reader.hasNext()) {
                        if (result.size() >= COMMENTS_PER_PAGE) throw new IOException("Too many comments");
                        result.add(new Comment(reader));
                    }
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
        if (!hasResult || pages < 0 || pages > MAX_PAGE) throw new IOException("Invalid comment page");
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
        String url = String.format(Locale.US, Utility.getApiBaseUrl() + "galleries/%d/comments", id);
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
