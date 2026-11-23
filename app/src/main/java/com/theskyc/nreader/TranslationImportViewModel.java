package com.theskyc.nreader;

import android.content.Context;
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

public class TranslationImportViewModel extends ViewModel {
    interface ImportOperation {
        boolean apply(Context context, Uri uri) throws IOException;
    }

    static final class Result {
        final boolean changed;
        final Throwable error;
        private boolean consumed;

        Result(boolean changed, Throwable error) {
            this.changed = changed;
            this.error = error;
        }

        boolean consume() {
            if (consumed) return false;
            consumed = true;
            return true;
        }
    }

    private final MutableLiveData<Result> result = new MutableLiveData<>();
    private final ImportOperation operation;
    private boolean running;

    public TranslationImportViewModel() {
        this(TranslationImportViewModel::importPack);
    }

    TranslationImportViewModel(ImportOperation operation) {
        this.operation = operation;
    }

    LiveData<Result> result() {
        return result;
    }

    boolean start(Context context, Uri uri) {
        if (running) return false;
        running = true;
        Context app = context.getApplicationContext();
        new Thread(() -> {
            Result completed;
            try {
                completed = new Result(operation.apply(app, uri), null);
            } catch (IOException | RuntimeException error) {
                LogUtility.e("Unable to install tag translation pack", error);
                completed = new Result(false, error);
            }
            Result outcome = completed;
            new Handler(Looper.getMainLooper()).post(() -> {
                running = false;
                result.setValue(outcome);
            });
        }, "Tag translation import").start();
        return true;
    }

    private static boolean importPack(Context context, Uri uri) throws IOException {
        try (InputStream input = context.getContentResolver().openInputStream(uri)) {
            if (input == null) throw new IOException("Unable to open selected translation pack");
            return TagTranslationCatalog.apply(Database.getDatabase(), input, context.getCacheDir(),
                () -> Thread.currentThread().isInterrupted());
        }
    }
}
