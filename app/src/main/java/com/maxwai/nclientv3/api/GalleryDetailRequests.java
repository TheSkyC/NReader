// NReader modifications by TheSkyC (2026); see NOTICE.
package com.maxwai.nclientv3.api;

import android.content.Context;
import android.os.Parcel;

import androidx.annotation.NonNull;

import com.maxwai.nclientv3.api.components.GenericGallery;
import com.maxwai.nclientv3.settings.AuthStore;
import com.maxwai.nclientv3.settings.Global;
import com.maxwai.nclientv3.utility.LogUtility;
import com.maxwai.nclientv3.utility.Utility;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Shares only active detail requests; completed results are not cached here. */
public final class GalleryDetailRequests {
    /** Callbacks must return promptly; subscribers share an ordered delivery queue. */
    public interface Callback {
        void onSuccess(List<GenericGallery> galleries);
        void onRelated(List<SimpleGallery> related);
        void onFailure(Exception error);
        void onEnd();
    }

    private static final Object lock = new Object();
    private static final Map<Key, Entry> entries = new HashMap<>();

    private GalleryDetailRequests() {
    }

    public static Subscription open(@NonNull Context context, int id, @NonNull Callback callback) {
        long identity = AuthStore.identityGeneration();
        Entry entry;
        Subscription subscription;
        synchronized (lock) {
            Key key = new Key(id, identity, Utility.getBaseUrl(), Global.getMirrorGeneration());
            entry = identity < 0 ? null : entries.get(key);
            if (entry == null) {
                entry = new Entry(context.getApplicationContext(), key);
                if (identity >= 0) entries.put(key, entry);
            }
            subscription = new Subscription(entry, callback);
            entry.subscribers.add(subscription);
            if (entry.main != null) entry.enqueue(subscription, Stage.MAIN, entry.main, null);
            if (entry.related != null) entry.enqueue(subscription, Stage.RELATED, entry.related, null);
            if (entry.failure != null) entry.enqueue(subscription, Stage.FAILURE, null, entry.failure);
            if (!entry.started) {
                entry.started = true;
                if (!entry.isCurrent() || !entry.inspector.getUrl().startsWith(key.baseUrl)) entry.failWithoutStarting();
                else entry.inspector.start();
            }
        }
        return subscription;
    }

    public static final class Subscription {
        private final Entry entry;
        private final Callback callback;
        private final AtomicBoolean active = new AtomicBoolean(true);
        private boolean failed;

        private Subscription(Entry entry, Callback callback) {
            this.entry = entry;
            this.callback = callback;
        }

        /** Stops future deliveries where possible; a callback already in progress may finish. */
        public void cancelRequest() {
            synchronized (lock) {
                if (!active.getAndSet(false)) return;
                entry.subscribers.remove(this);
                if (entry.subscribers.isEmpty() && !entry.terminated) {
                    entry.terminated = true;
                    entries.remove(entry.key, entry);
                    entry.inspector.cancelRequest();
                    entry.delivery.shutdown();
                }
            }
        }
    }

    private enum Stage { MAIN, RELATED, FAILURE, END }

    private static final class Key {
        final int id;
        final long identity;
        final String baseUrl;
        final long mirrorGeneration;

        Key(int id, long identity, String baseUrl, long mirrorGeneration) {
            this.id = id;
            this.identity = identity;
            this.baseUrl = baseUrl;
            this.mirrorGeneration = mirrorGeneration;
        }

        @Override public boolean equals(Object other) {
            if (!(other instanceof Key)) return false;
            Key key = (Key) other;
            return id == key.id && identity == key.identity && mirrorGeneration == key.mirrorGeneration
                && baseUrl.equals(key.baseUrl);
        }

        @Override public int hashCode() {
            return 31 * (31 * (31 * id + Long.hashCode(identity)) + baseUrl.hashCode())
                + Long.hashCode(mirrorGeneration);
        }
    }

    private static final class Entry {
        final Key key;
        final List<Subscription> subscribers = new ArrayList<>();
        final ExecutorService delivery = Executors.newSingleThreadExecutor();
        final InspectorV3 inspector;
        byte[] main;
        byte[] related;
        Exception failure;
        boolean started;
        boolean terminated;

        Entry(Context context, Key key) {
            this.key = key;
            inspector = InspectorV3.galleryInspector(context, key.id, new InspectorV3.DefaultInspectorResponse() {
                @Override public void onSuccess(List<GenericGallery> galleries) {
                    publish(Stage.MAIN, snapshot(galleries), null);
                }

                @Override public void onFailure(Exception error) {
                    publish(Stage.FAILURE, null, error);
                }

                @Override public void onEnd() {
                    finish();
                }
            });
            inspector.setDeferredRelatedResponse(items -> publish(Stage.RELATED, snapshot(items), null));
        }

        void failWithoutStarting() {
            publish(Stage.FAILURE, null, new IOException("Gallery request source is changing"));
            finish();
        }

        void publish(Stage stage, byte[] data, Exception error) {
            synchronized (lock) {
                if (terminated) return;
                if (stage == Stage.MAIN) main = data;
                if (stage == Stage.RELATED) related = data;
                if (stage == Stage.FAILURE) failure = error;
                for (Subscription subscriber : subscribers) enqueue(subscriber, stage, data, error);
            }
        }

        void finish() {
            synchronized (lock) {
                if (terminated) return;
                terminated = true;
                entries.remove(key, this);
                for (Subscription subscriber : subscribers) enqueue(subscriber, Stage.END, null, null);
                subscribers.clear();
                delivery.shutdown();
            }
        }

        void enqueue(Subscription subscriber, Stage stage, byte[] data, Exception error) {
            delivery.execute(() -> {
                synchronized (lock) {
                    if (!subscriber.active.get()) return;
                }
                try {
                    if (stage != Stage.END && !isCurrent()) {
                        if (!subscriber.failed) {
                            subscriber.failed = true;
                            subscriber.callback.onFailure(new IOException("Gallery request source changed"));
                        }
                        return;
                    }
                    if (subscriber.failed && stage != Stage.END) return;
                    if (stage == Stage.MAIN || stage == Stage.RELATED) {
                        List<?> values;
                        try {
                            values = readSnapshot(data);
                        } catch (RuntimeException exception) {
                            subscriber.failed = true;
                            subscriber.callback.onFailure(new IOException("Unable to read gallery details", exception));
                            return;
                        }
                        if (!subscriber.active.get()) return;
                        if (!isCurrent()) {
                            subscriber.failed = true;
                            subscriber.callback.onFailure(new IOException("Gallery request source changed"));
                            return;
                        }
                        if (stage == Stage.MAIN) subscriber.callback.onSuccess(galleries(values));
                        else subscriber.callback.onRelated(related(values));
                    }
                    else if (stage == Stage.FAILURE) {
                        subscriber.failed = true;
                        subscriber.callback.onFailure(error);
                    } else subscriber.callback.onEnd();
                } catch (RuntimeException exception) {
                    LogUtility.w("Unable to deliver gallery detail result", exception);
                }
            });
        }

        boolean isCurrent() {
            return key.identity >= 0 && key.identity == AuthStore.identityGeneration()
                && key.baseUrl.equals(Utility.getBaseUrl()) && key.mirrorGeneration == Global.getMirrorGeneration();
        }
    }

    @SuppressWarnings("unchecked")
    private static List<GenericGallery> galleries(List<?> values) {
        return (List<GenericGallery>) values;
    }

    @SuppressWarnings("unchecked")
    private static List<SimpleGallery> related(List<?> values) {
        return (List<SimpleGallery>) values;
    }

    private static byte[] snapshot(List<?> values) {
        Parcel parcel = Parcel.obtain();
        try {
            parcel.writeList(values);
            return parcel.marshall();
        } finally {
            parcel.recycle();
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> List<T> readSnapshot(byte[] data) {
        Parcel parcel = Parcel.obtain();
        try {
            parcel.unmarshall(data, 0, data.length);
            parcel.setDataPosition(0);
            return (List<T>) parcel.readArrayList(GenericGallery.class.getClassLoader());
        } finally {
            parcel.recycle();
        }
    }
}
