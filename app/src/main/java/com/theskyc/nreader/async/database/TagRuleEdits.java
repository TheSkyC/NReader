package com.theskyc.nreader.async.database;

import android.database.sqlite.SQLiteDatabase;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.theskyc.nreader.api.components.Tag;
import com.theskyc.nreader.api.enums.TagStatus;
import com.theskyc.nreader.api.enums.TagType;
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
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Serializes local rule edits without holding the UI thread across a SQLite write. */
public final class TagRuleEdits {
    public enum Outcome {
        SAVED, PREVIEW, NO_MATCHES, TOO_MANY, LIMIT_REACHED,
        UNDO_UNAVAILABLE, UNDO_STALE, SUPERSEDED, FAILED
    }

    public static final int MAX_BULK_TAGS = 200;

    public static final class Result {
        public final Outcome outcome;
        public final TagStatus status;
        public final int affected;

        private Result(Outcome outcome, TagStatus status, int affected) {
            this.outcome = outcome;
            this.status = status;
            this.affected = affected;
        }
    }

    private static final MutableLiveData<Void> CHANGES = new MutableLiveData<>();
    private static final AtomicLong REVISION = new AtomicLong();
    private static final int QUEUE_CAPACITY = 32;
    private static final Object UNDO_LOCK = new Object();
    @Nullable
    private static BulkUndo lastBulkUndo;

    private static final class UndoEntry {
        private final Tag tag;
        private final TagStatus before;
        private final TagStatus after;

        private UndoEntry(Tag tag, TagStatus before, TagStatus after) {
            this.tag = tag;
            this.before = before;
            this.after = after;
        }
    }

    private static final class BulkUndo {
        private final List<UndoEntry> entries;

        private BulkUndo(List<UndoEntry> entries) {
            this.entries = entries;
        }
    }

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

    public static void bulkSetMatching(@NonNull String query, @NonNull TagType type,
                                       boolean sortByName, @Nullable Locale appLocale,
                                       @Nullable TagStatus statusFilter, boolean translatedOnly,
                                       @NonNull TagStatus status, boolean enforceLimit,
                                       Consumer<Result> callback) {
        INSTANCE.enqueueBulkSetMatching(query, type, sortByName, appLocale, statusFilter,
            translatedOnly, status, enforceLimit, callback);
    }

    public static void previewMatching(@NonNull String query, @NonNull TagType type,
                                       boolean sortByName, @Nullable Locale appLocale,
                                       @Nullable TagStatus statusFilter, boolean translatedOnly,
                                       Consumer<Result> callback) {
        INSTANCE.enqueuePreviewMatching(query, type, sortByName, appLocale, statusFilter,
            translatedOnly, callback);
    }

    public static boolean hasUndo() {
        synchronized (UNDO_LOCK) {
            return lastBulkUndo != null;
        }
    }

    public static void undoLastBulk(Consumer<Result> callback) {
        INSTANCE.enqueueUndo(callback);
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

    void enqueueBulkSet(List<Tag> tags, TagStatus status, boolean enforceLimit, Consumer<Result> callback) {
        enqueueBulkSet(tags, status, enforceLimit, false, callback);
    }

    void enqueueBulkSet(List<Tag> tags, TagStatus status, boolean enforceLimit,
                        boolean recordUndo, Consumer<Result> callback) {
        long generation = Login.onlineTagsRestoreGeneration();
        submit(generation, () -> bulkSetStored(tags, status, enforceLimit, recordUndo, generation), callback);
    }

    void enqueueUndo(Consumer<Result> callback) {
        long generation = Login.onlineTagsRestoreGeneration();
        submit(generation, () -> undoStored(generation), callback);
    }

    private void enqueueBulkSetMatching(String query, TagType type, boolean sortByName,
                                        Locale appLocale, TagStatus statusFilter,
                                        boolean translatedOnly, TagStatus status,
                                        boolean enforceLimit, Consumer<Result> callback) {
        long generation = Login.onlineTagsRestoreGeneration();
        submit(generation, () -> {
            List<Tag> tags = Queries.TagTable.getFilteredTags(query, type, sortByName, appLocale,
                statusFilter, translatedOnly, MAX_BULK_TAGS + 1);
            if (tags.isEmpty()) return new Result(Outcome.NO_MATCHES, status, 0);
            if (tags.size() > MAX_BULK_TAGS) return new Result(Outcome.TOO_MANY, status, 0);
            return bulkSetStored(tags, status, enforceLimit, true, generation);
        }, callback);
    }

    void enqueuePreviewMatching(String query, TagType type, boolean sortByName,
                                Locale appLocale, TagStatus statusFilter,
                                boolean translatedOnly, Consumer<Result> callback) {
        long generation = Login.onlineTagsRestoreGeneration();
        submit(generation, () -> {
            List<Tag> tags = Queries.TagTable.getFilteredTags(query, type, sortByName, appLocale,
                statusFilter, translatedOnly, MAX_BULK_TAGS + 1);
            if (tags.isEmpty()) return new Result(Outcome.NO_MATCHES, null, 0);
            if (tags.size() > MAX_BULK_TAGS) return new Result(Outcome.TOO_MANY, null, 0);
            return new Result(Outcome.PREVIEW, null, tags.size());
        }, callback);
    }

    private void submit(long generation, Supplier<Result> operation, Consumer<Result> callback) {
        if (Login.isRestoringTagData()) {
            delivery.execute(() -> callback.accept(new Result(Outcome.SUPERSEDED, null, 0)));
            return;
        }
        try {
            worker.execute(() -> {
                Result result;
                try {
                    result = operation.get();
                } catch (RuntimeException error) {
                    LogUtility.w("Could not save local tag rules", error);
                    result = new Result(Outcome.FAILED, null, 0);
                }
                Result completed = result;
                if (completed.outcome == Outcome.SAVED) publishChange();
                delivery.execute(() -> {
                    boolean current = generation == Login.onlineTagsRestoreGeneration() && !Login.isRestoringTagData();
                    callback.accept(current ? completed : new Result(Outcome.SUPERSEDED, null, 0));
                });
            });
        } catch (RejectedExecutionException busy) {
            delivery.execute(() -> callback.accept(new Result(Outcome.FAILED, null, 0)));
        }
    }

    private static Result cycleStored(Tag tag, boolean enforceLimit, boolean countAvoided, long generation) {
        if (tag.getId() <= 0 || tag.getType() == null || tag.getType().getId() <= 0) {
            return new Result(Outcome.FAILED, null, 0);
        }
        return write(generation, () -> {
            TagStatus current = Queries.TagTable.storedRuleStatus(tag);
            if (enforceLimit && current == TagStatus.DEFAULT
                && TagV2.getListPrefer(countAvoided).size() >= TagV2.MAXTAGS) {
                return new Result(Outcome.LIMIT_REACHED, current, 0);
            }
            TagStatus next;
            switch (current) {
                case ACCEPTED: next = TagStatus.AVOIDED; break;
                case AVOIDED: next = TagStatus.DEFAULT; break;
                default: next = TagStatus.ACCEPTED; break;
            }
            return Queries.TagTable.storeRule(tag, next)
                ? new Result(Outcome.SAVED, next, 1) : new Result(Outcome.FAILED, null, 0);
        });
    }

    private static Result bulkSetStored(List<Tag> tags, TagStatus status, boolean enforceLimit,
                                        boolean recordUndo, long generation) {
        if (tags.isEmpty()) return new Result(Outcome.NO_MATCHES, status, 0);
        boolean countAvoided = Global.removeAvoidedGalleries();
        List<UndoEntry> applied = new java.util.ArrayList<>();
        Result result = write(generation, () -> {
            int currentCount = enforceLimit && status == TagStatus.ACCEPTED
                ? TagV2.getListPrefer(countAvoided).size() : 0;
            int delta = 0;
            for (Tag tag : tags) {
                TagStatus current = Queries.TagTable.storedRuleStatus(tag);
                delta += ruleCountContribution(status, countAvoided)
                    - ruleCountContribution(current, countAvoided);
            }
            if (enforceLimit && status == TagStatus.ACCEPTED && currentCount + delta > TagV2.MAXTAGS) {
                return new Result(Outcome.LIMIT_REACHED, status, 0);
            }
            int affected = 0;
            for (Tag tag : tags) {
                TagStatus current = Queries.TagTable.storedRuleStatus(tag);
                if (current == status) continue;
                if (!Queries.TagTable.storeRule(tag, status)) return new Result(Outcome.FAILED, null, 0);
                applied.add(new UndoEntry(snapshot(tag), current, status));
                affected++;
            }
            return new Result(Outcome.SAVED, status, affected);
        });
        if (recordUndo && result.outcome == Outcome.SAVED && !applied.isEmpty()) {
            synchronized (UNDO_LOCK) {
                lastBulkUndo = new BulkUndo(new java.util.ArrayList<>(applied));
            }
        }
        return result;
    }

    private static int ruleCountContribution(TagStatus status, boolean countAvoided) {
        return status == TagStatus.ACCEPTED || (countAvoided && status == TagStatus.AVOIDED) ? 1 : 0;
    }

    private static Result undoStored(long generation) {
        BulkUndo undo;
        synchronized (UNDO_LOCK) {
            undo = lastBulkUndo;
        }
        if (undo == null) return new Result(Outcome.UNDO_UNAVAILABLE, null, 0);
        Result result = write(generation, () -> {
            for (UndoEntry entry : undo.entries) {
                if (Queries.TagTable.storedRuleStatus(entry.tag) != entry.after) {
                    return new Result(Outcome.UNDO_STALE, null, 0);
                }
            }
            int affected = 0;
            for (UndoEntry entry : undo.entries) {
                if (!Queries.TagTable.storeRule(entry.tag, entry.before)) {
                    return new Result(Outcome.FAILED, null, 0);
                }
                affected++;
            }
            return new Result(Outcome.SAVED, TagStatus.DEFAULT, affected);
        });
        if (result.outcome == Outcome.SAVED) {
            synchronized (UNDO_LOCK) {
                if (lastBulkUndo == undo) lastBulkUndo = null;
            }
        } else if (result.outcome == Outcome.UNDO_STALE) {
            synchronized (UNDO_LOCK) {
                if (lastBulkUndo == undo) lastBulkUndo = null;
            }
        }
        return result;
    }

    static void clearUndo() {
        synchronized (UNDO_LOCK) {
            lastBulkUndo = null;
        }
    }

    private static Result clearStored(long generation) {
        return write(generation, () -> {
            Queries.TagTable.resetAllStatus();
            return new Result(Outcome.SAVED, TagStatus.DEFAULT, 0);
        });
    }

    private static Result write(long generation, Supplier<Result> operation) {
        if (generation != Login.onlineTagsRestoreGeneration()) return new Result(Outcome.SUPERSEDED, null, 0);
        SQLiteDatabase database = Queries.db;
        if (database == null) return new Result(Outcome.FAILED, null, 0);
        database.beginTransactionNonExclusive();
        try {
            // Check after acquiring the writer lock: a restore may have run while we waited.
            if (Login.isRestoringTagData() || generation != Login.onlineTagsRestoreGeneration()) {
                return new Result(Outcome.SUPERSEDED, null, 0);
            }
            Result result = operation.get();
            if (result.outcome == Outcome.SAVED) database.setTransactionSuccessful();
            return result;
        } finally {
            database.endTransaction();
        }
    }
}
