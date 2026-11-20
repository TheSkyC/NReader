// NReader modifications by TheSkyC (2026); see NOTICE.
package com.theskyc.nreader.async;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.work.Data;
import androidx.work.ListenableWorker;
import androidx.work.WorkerParameters;
import androidx.work.impl.WorkManagerImpl;
import androidx.work.impl.utils.taskexecutor.WorkManagerTaskExecutor;

import com.google.common.util.concurrent.Futures;
import com.theskyc.nreader.async.converters.CreatePdfOrZip;
import com.theskyc.nreader.async.downloader.DownloadGalleryV2;
import com.theskyc.nreader.components.activities.CrashApplication;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.Executor;

import kotlin.coroutines.EmptyCoroutineContext;

@RunWith(RobolectricTestRunner.class)
public class LegacyWorkerFactoryTest {
    @Test
    public void restoresEveryPreviouslyScheduledWorker() {
        Context context = RuntimeEnvironment.getApplication();
        LegacyWorkerFactory factory = new LegacyWorkerFactory();
        Executor executor = Runnable::run;
        WorkerParameters parameters = new WorkerParameters(UUID.randomUUID(), Data.EMPTY, Collections.emptyList(),
            new WorkerParameters.RuntimeExtras(), 0, 0, executor, EmptyCoroutineContext.INSTANCE,
            new WorkManagerTaskExecutor(executor), factory,
            (app, id, data) -> Futures.immediateFuture(null),
            (app, id, info) -> Futures.immediateFuture(null));

        assertEquals(ScrapeTags.class, factory.createWorker(context, "com.maxwai.nclientv3.async.ScrapeTags", parameters).getClass());
        assertEquals(UpdateCheckWorker.class, factory.createWorker(context, "com.maxwai.nclientv3.async.UpdateCheckWorker", parameters).getClass());
        assertEquals(UpdateDownloadWorker.class, factory.createWorker(context, "com.maxwai.nclientv3.async.UpdateDownloadWorker", parameters).getClass());
        assertEquals(CreatePdfOrZip.class, factory.createWorker(context, "com.maxwai.nclientv3.async.converters.CreatePdfOrZip", parameters).getClass());
        assertEquals(DownloadGalleryV2.class, factory.createWorker(context, "com.maxwai.nclientv3.async.downloader.DownloadGalleryV2", parameters).getClass());
        assertNull(factory.createWorker(context, "com.maxwai.nclientv3.async.UnknownWorker", parameters));
        assertNull(factory.createWorker(context, ScrapeTags.class.getName(), parameters));
    }

    @Test
    public void applicationProvidesCompatibilityFactory() {
        assertTrue(((CrashApplication) RuntimeEnvironment.getApplication())
            .getWorkManagerConfiguration().getWorkerFactory() instanceof LegacyWorkerFactory);
        assertTrue(WorkManagerImpl.getInstance(RuntimeEnvironment.getApplication())
            .getConfiguration().getWorkerFactory() instanceof LegacyWorkerFactory);
    }
}
