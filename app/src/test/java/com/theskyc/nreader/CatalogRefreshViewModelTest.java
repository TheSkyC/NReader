package com.theskyc.nreader;

import androidx.work.Data;
import androidx.work.WorkInfo;

import com.theskyc.nreader.async.ScrapeTags;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class CatalogRefreshViewModelTest {
    @Test public void waitingAndRunningAreNotReportedAsFailures() {
        assertEquals(CatalogRefreshViewModel.Status.QUEUED,
            CatalogRefreshViewModel.statusFor(WorkInfo.State.ENQUEUED, Data.EMPTY));
        assertEquals(CatalogRefreshViewModel.Status.QUEUED,
            CatalogRefreshViewModel.statusFor(WorkInfo.State.BLOCKED, Data.EMPTY));
        assertEquals(CatalogRefreshViewModel.Status.RUNNING,
            CatalogRefreshViewModel.statusFor(WorkInfo.State.RUNNING, Data.EMPTY));
    }

    @Test public void onlyKnownSuccessOutputsAreReportedAsCompleted() {
        assertEquals(CatalogRefreshViewModel.Status.CHECKED,
            CatalogRefreshViewModel.statusFor(WorkInfo.State.SUCCEEDED, output(ScrapeTags.CHECKED)));
        assertEquals(CatalogRefreshViewModel.Status.NO_SOURCE,
            CatalogRefreshViewModel.statusFor(WorkInfo.State.SUCCEEDED, output(ScrapeTags.NO_SOURCE)));
        assertEquals(CatalogRefreshViewModel.Status.FAILED,
            CatalogRefreshViewModel.statusFor(WorkInfo.State.SUCCEEDED, Data.EMPTY));
        assertEquals(CatalogRefreshViewModel.Status.FAILED,
            CatalogRefreshViewModel.statusFor(WorkInfo.State.SUCCEEDED, output("unrecognized")));
    }

    @Test public void failedAndCancelledWorkRemainDistinct() {
        assertEquals(CatalogRefreshViewModel.Status.FAILED,
            CatalogRefreshViewModel.statusFor(WorkInfo.State.FAILED, Data.EMPTY));
        assertEquals(CatalogRefreshViewModel.Status.CANCELLED,
            CatalogRefreshViewModel.statusFor(WorkInfo.State.CANCELLED, Data.EMPTY));
    }

    private Data output(String value) {
        return new Data.Builder().putString(ScrapeTags.CHECK_RESULT, value).build();
    }
}
