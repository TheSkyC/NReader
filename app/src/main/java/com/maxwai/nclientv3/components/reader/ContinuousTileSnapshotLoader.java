// NReader modifications by TheSkyC (2026); see NOTICE.
package com.maxwai.nclientv3.components.reader;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.bumptech.glide.Priority;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.Options;
import com.bumptech.glide.load.data.DataFetcher;
import com.bumptech.glide.load.model.ModelLoader;
import com.bumptech.glide.load.model.ModelLoaderFactory;
import com.bumptech.glide.load.model.MultiModelLoaderFactory;
import com.bumptech.glide.signature.ObjectKey;
import com.maxwai.nclientv3.utility.LogUtility;

import java.io.IOException;
import java.io.InputStream;

/** Gives Glide a tracked stream from the same snapshot used by the region decoder. */
final class ContinuousTileSnapshotLoader implements ModelLoader<ContinuousTileSnapshotSession, InputStream> {
    @Nullable @Override
    public LoadData<InputStream> buildLoadData(@NonNull ContinuousTileSnapshotSession session, int width, int height,
                                               @NonNull Options options) {
        return new LoadData<>(new ObjectKey(session), new Fetcher(session));
    }

    @Override public boolean handles(@NonNull ContinuousTileSnapshotSession session) { return true; }

    static final class Factory implements ModelLoaderFactory<ContinuousTileSnapshotSession, InputStream> {
        @NonNull @Override public ModelLoader<ContinuousTileSnapshotSession, InputStream> build(
            @NonNull MultiModelLoaderFactory multiFactory) {
            return new ContinuousTileSnapshotLoader();
        }
        @Override public void teardown() {}
    }

    private static final class Fetcher implements DataFetcher<InputStream> {
        private final ContinuousTileSnapshotSession session;
        @Nullable private InputStream stream;
        private boolean canceled;
        private boolean cleaned;

        Fetcher(ContinuousTileSnapshotSession session) { this.session = session; }

        @Override public void loadData(@NonNull Priority priority, @NonNull DataCallback<? super InputStream> callback) {
            InputStream opened;
            try {
                synchronized (this) {
                    if (canceled || cleaned) return;
                    opened = session.openFallbackStream();
                    stream = opened;
                }
            } catch (IOException exception) {
                callback.onLoadFailed(exception);
                return;
            }
            callback.onDataReady(opened);
        }

        @Override public synchronized void cleanup() {
            cleaned = true;
            if (stream == null) return;
            try {
                stream.close();
                stream = null;
            } catch (IOException exception) {
                LogUtility.w("Could not release image snapshot stream", exception);
            }
        }

        @Override public synchronized void cancel() { canceled = true; }
        @NonNull @Override public Class<InputStream> getDataClass() { return InputStream.class; }
        @NonNull @Override public DataSource getDataSource() { return DataSource.LOCAL; }
    }
}
