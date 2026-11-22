package com.theskyc.nreader.async.database;

import android.database.sqlite.SQLiteDatabase;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.WorkerThread;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.theskyc.nreader.api.components.Tag;
import com.theskyc.nreader.api.enums.TagStatus;
import com.theskyc.nreader.settings.Global;
import com.theskyc.nreader.settings.Login;
import com.theskyc.nreader.settings.TagV2;
import com.theskyc.nreader.utility.LogUtility;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Serializes local rule edits without holding the UI thread across a SQLite write. */
public final class TagRuleEdits {
    public enum Outcome { SAVED, LIMIT_REACHED, SUPERSEDED, FAILED }

    public static final class Result {
        public final Outcome outcome;
        public final TagStatus status;

        private Result(Outcome outcome, TagStatus status) {
            this.outcome = outcome;
            this.status = status;
        }
    }

    private static final MutableLiveData<Void> CHANGES = new MutableLiveData<>();
    private static final AtomicLong REVISION = new AtomicLong();
    private static final int QUEUE_CAPACITY = 32;

    private static final class MainDelivery {
        private static final Handler HANDLER = new Handler(Looper.getMainLooper());
    }

    private static final TagRuleEdits INSTANCE = new TagRuleEdits(
        new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(QUEUE_CAPACITY)),
        action -> MainDelivery.HANDLER.post(action));

    private final Executor worker;
    private final Executor delivery;

    TagRuleEdits(Executor worker, Executor delivery) {
        this.worker = worker;
        this.delivery = delivery;
    }

    public static LiveData<Void> changes() {
        return CHANGES;
    }

    public static void onRestoreFinished() {
        publishChange();
    }

    public static long revision() {
        return REVISION.get();
    }

    private static void publishChange() {
        REVISION.incrementAndGet();
        CHANGES.postValue(null);
    }

    public static void cycle(Tag tag, boolean enforceLimit, Consumer<Result> callback) {
        INSTANCE.enqueueCycle(tag, enforceLimit, callback);
    }

    public static void clear(Consumer<Result> callback) {
        INSTANCE.enqueueClear(callback);
    }

    @WorkerThread
    public static TagStatus readStoredStatus(Tag tag) {
        return Queries.TagTable.storedRuleStatus(tag);
    }

    private static Tag snapshot(Tag tag) {
        return new Tag(tag.getName(), tag.getCount(), tag.getId(), tag.getType(), TagStatus.DEFAULT);
    }

    void enqueueCycle(Tag tag, boolean enforceLimit, Consumer<Result> callback) {
        Tag request = snapshot(tag);
        boolean countAvoided = Global.removeAvoidedGalleries();
        long generation = Login.onlineTagsRestoreGeneration();
        submit(generation, () -> cycleStored(request, enforceLimit, countAvoided, generation), callback);
    }

    void enqueueClear(Consumer<Result> callback) {
        long generation = Login.onlineTagsRestoreGeneration();
        submit(generation, () -> clearStored(generation), callback);
    }

    private void submit(long generation, Supplier<Result> operation, Consumer<Result> callback) {
        if (Login.isRestoringTagData()) {
            delivery.execute(() -> callback.accept(new Result(Outcome.SUPERSEDED, null)));
            return;
        }
        try {
            worker.execute(() -> {
                Result result;
                try {
                    result = operation.get();
                } catch (RuntimeException error) {
                    LogUtility.w("Could not save local tag rules", error);
                    result = new Result(Outcome.FAILED, null);
                }
                Result completed = result;
                if (completed.outcome == Outcome.SAVED) publishChange();
                delivery.execute(() -> {
                    boolean current = generation == Login.onlineTagsRestoreGeneration() && !Login.isRestoringTagData();
                    callback.accept(current ? completed : new Result(Outcome.SUPERSEDED, null));
                });
            });
        } catch (RejectedExecutionException busy) {
            delivery.execute(() -> callback.accept(new Result(Outcome.FAILED, null)));
        }
    }

    private static Result cycleStored(Tag tag, boolean enforceLimit, boolean countAvoided, long generation) {
        if (tag.getId() <= 0 || tag.getType() == null || tag.getType().getId() <= 0) {
            return new Result(Outcome.FAILED, null);
        }
        return write(generation, () -> {
            TagStatus current = Queries.TagTable.storedRuleStatus(tag);
            if (enforceLimit && current == TagStatus.DEFAULT
                && TagV2.getListPrefer(countAvoided).size() >= TagV2.MAXTAGS) {
                return new Result(Outcome.LIMIT_REACHED, current);
            }
            TagStatus next;
            switch (current) {
                case ACCEPTED: next = TagStatus.AVOIDED; break;
                case AVOIDED: next = TagStatus.DEFAULT; break;
                default: next = TagStatus.ACCEPTED; break;
            }
            return Queries.TagTable.storeRule(tag, next)
                ? new Result(Outcome.SAVED, next) : new Result(Outcome.FAILED, null);
        });
    }

    private static Result clearStored(long generation) {
        return write(generation, () -> {
            Queries.TagTable.resetAllStatus();
            return new Result(Outcome.SAVED, TagStatus.DEFAULT);
        });
    }

    private static Result write(long generation, Supplier<Result> operation) {
        if (generation != Login.onlineTagsRestoreGeneration()) return new Result(Outcome.SUPERSEDED, null);
        SQLiteDatabase database = Queries.db;
        if (database == null) return new Result(Outcome.FAILED, null);
        database.beginTransactionNonExclusive();
        try {
            // Check after acquiring the writer lock: a restore may have run while we waited.
            if (Login.isRestoringTagData() || generation != Login.onlineTagsRestoreGeneration()) {
                return new Result(Outcome.SUPERSEDED, null);
            }
            Result result = operation.get();
            if (result.outcome == Outcome.SAVED) database.setTransactionSuccessful();
            return result;
        } finally {
            database.endTransaction();
        }
    }
}
