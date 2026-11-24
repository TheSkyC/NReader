package com.theskyc.nreader;

import android.content.Context;

import androidx.core.content.ContextCompat;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModel;
import androidx.work.Data;
import androidx.work.Operation;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;

import com.google.common.util.concurrent.ListenableFuture;
import com.theskyc.nreader.async.ScrapeTags;
import com.theskyc.nreader.utility.LogUtility;

import java.util.UUID;
import java.util.concurrent.ExecutionException;

public class CatalogRefreshViewModel extends ViewModel {
    enum Status { QUEUED, RUNNING, CHECKED, UPDATED, NO_SOURCE, FAILED, CANCELLED }

    private final MutableLiveData<Status> status = new MutableLiveData<>();
    private UUID requestId;
    private LiveData<WorkInfo> work;
    private Observer<WorkInfo> workObserver;

    LiveData<Status> status() {
        return status;
    }

    UUID requestId() {
        return requestId;
    }

    void start(Context context) {
        clearRequest();
        Context app = context.getApplicationContext();
        UUID id = UUID.randomUUID();
        requestId = id;
        status.setValue(Status.QUEUED);
        try {
            Operation operation = ScrapeTags.requestRefresh(app, id);
            operation.getResult().addListener(() -> {
                if (!id.equals(requestId)) return;
                try {
                    operation.getResult().get();
                    observeWork(app, id);
                } catch (ExecutionException | RuntimeException error) {
                    LogUtility.w("Unable to schedule tag catalog check", error);
                    status.setValue(Status.FAILED);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    status.setValue(Status.FAILED);
                }
            }, ContextCompat.getMainExecutor(app));
        } catch (RuntimeException error) {
            LogUtility.w("Unable to schedule tag catalog check", error);
            status.setValue(Status.FAILED);
        }
    }

    void restore(Context context, UUID id) {
        if (requestId != null) return;
        requestId = id;
        status.setValue(Status.QUEUED);
        try {
            Context app = context.getApplicationContext();
            observeWork(app, id);
            ListenableFuture<WorkInfo> lookup = WorkManager.getInstance(app).getWorkInfoById(id);
            lookup.addListener(() -> {
                if (!id.equals(requestId)) return;
                try {
                    if (lookup.get() == null) status.setValue(Status.FAILED);
                } catch (ExecutionException | RuntimeException error) {
                    LogUtility.w("Unable to find tag catalog check", error);
                    status.setValue(Status.FAILED);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    status.setValue(Status.FAILED);
                }
            }, ContextCompat.getMainExecutor(app));
        } catch (RuntimeException error) {
            LogUtility.w("Unable to restore tag catalog check", error);
            status.setValue(Status.FAILED);
        }
    }

    private void observeWork(Context context, UUID id) {
        work = WorkManager.getInstance(context).getWorkInfoByIdLiveData(id);
        workObserver = info -> {
            if (id.equals(requestId)) status.setValue(info == null
                ? Status.FAILED : statusFor(info.getState(), info.getOutputData()));
        };
        work.observeForever(workObserver);
    }

    static Status statusFor(WorkInfo.State state, Data output) {
        switch (state) {
            case ENQUEUED:
            case BLOCKED:
                return Status.QUEUED;
            case RUNNING:
                return Status.RUNNING;
            case SUCCEEDED:
                String result = output.getString(ScrapeTags.CHECK_RESULT);
                if (ScrapeTags.CHECKED.equals(result)) return Status.CHECKED;
                if (ScrapeTags.UPDATED.equals(result)) return Status.UPDATED;
                if (ScrapeTags.NO_SOURCE.equals(result)) return Status.NO_SOURCE;
                return Status.FAILED;
            case CANCELLED:
                return Status.CANCELLED;
            default:
                return Status.FAILED;
        }
    }

    void clearRequest() {
        requestId = null;
        if (work != null) work.removeObserver(workObserver);
        work = null;
        workObserver = null;
    }

    @Override protected void onCleared() {
        clearRequest();
    }
}
