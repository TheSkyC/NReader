package com.theskyc.nreader;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import com.theskyc.nreader.async.database.TagTranslationCatalog;
import com.theskyc.nreader.settings.Database;
import com.theskyc.nreader.utility.LogUtility;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.util.concurrent.atomic.AtomicBoolean;

public class TranslationExportViewModel extends ViewModel {
    interface OutputOpener {
        OutputStream open(Context context, Uri destination) throws IOException;
    }

    static final class Selection {
        final String locale;
        final int revision;
        final long generation;

        Selection(String locale, int revision, long generation) {
            this.locale = locale;
            this.revision = revision;
            this.generation = generation;
        }
    }

    static final class ChangedSelectionException extends IOException {
        ChangedSelectionException() { super("Translation pack changed before export"); }
    }

    static final class Result {
        final Throwable error;
        private boolean consumed;

        Result(Throwable error) { this.error = error; }

        boolean consume() {
            if (consumed) return false;
            consumed = true;
            return true;
        }

        boolean isConsumed() { return consumed; }
    }

    private final MutableLiveData<Result> result = new MutableLiveData<>();
    private final MutableLiveData<Boolean> running = new MutableLiveData<>(false);
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final OutputOpener opener;

    public TranslationExportViewModel() {
        this((context, destination) -> context.getContentResolver().openOutputStream(destination, "wt"));
    }

    TranslationExportViewModel(OutputOpener opener) { this.opener = opener; }

    LiveData<Result> result() { return result; }
    LiveData<Boolean> running() { return running; }
    boolean isRunning() { return Boolean.TRUE.equals(running.getValue()); }
    boolean isCancelled() { return cancelled.get(); }

    boolean start(Context context, Uri destination, Selection selection) {
        if (isRunning()) return false;
        cancelled.set(false);
        result.setValue(null);
        running.setValue(true);
        Context app = context.getApplicationContext();
        new Thread(() -> {
            Throwable failure = null;
            try {
                export(app, destination, selection);
            } catch (IOException | RuntimeException error) {
                failure = error;
                if (!(error instanceof InterruptedIOException)) {
                    LogUtility.e("Unable to export tag translation pack", error.getClass().getSimpleName());
                }
            }
            Throwable completedFailure = failure;
            new Handler(Looper.getMainLooper()).post(() -> {
                Throwable outcome = completedFailure;
                if (outcome == null && cancelled.get()) outcome = new InterruptedIOException("Translation export cancelled");
                running.setValue(false);
                result.setValue(new Result(outcome));
            });
        }, "Tag translation export").start();
        return true;
    }

    void cancel() { cancelled.set(true); }

    @Override protected void onCleared() {
        cancel();
        super.onCleared();
    }

    private void checkCancelled() throws InterruptedIOException {
        if (cancelled.get() || Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("Translation export cancelled");
        }
    }

    private void export(Context context, Uri destination, Selection selection) throws IOException {
        if (selection == null || destination == null || !"content".equals(destination.getScheme())) {
            throw new IOException("Invalid translation export destination");
        }
        SQLiteDatabase database = Database.getDatabase();
        TagTranslationCatalog.PackState state = TagTranslationCatalog.installedPackStates(database).get(selection.locale);
        if (state == null || state.revision != selection.revision || state.generation != selection.generation) {
            throw new ChangedSelectionException();
        }
        try (SeekableByteChannel snapshot = TagTranslationCatalog.exportSnapshot(database, state,
                 context.getCacheDir(), cancelled::get)) {
            checkCancelled();
            try (OutputStream output = opener.open(context, destination)) {
                if (output == null) throw new IOException("Unable to open translation export destination");
                InputStream input = Channels.newInputStream(snapshot);
                byte[] buffer = new byte[8192];
                int length;
                while ((length = input.read(buffer)) != -1) {
                    checkCancelled();
                    output.write(buffer, 0, length);
                }
                checkCancelled();
            }
            checkCancelled();
        }
    }
}
