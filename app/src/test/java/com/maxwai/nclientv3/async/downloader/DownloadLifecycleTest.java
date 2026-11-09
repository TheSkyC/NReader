// NReader modifications by TheSkyC (2026); see NOTICE.
package com.maxwai.nclientv3.async.downloader;

import android.app.Application;
import android.content.Context;

import com.maxwai.nclientv3.api.components.Gallery;
import com.maxwai.nclientv3.api.local.LocalGallery;
import com.maxwai.nclientv3.async.database.DatabaseHelper;
import com.maxwai.nclientv3.async.database.Queries;
import com.maxwai.nclientv3.components.classes.ReadingPosition;
import com.maxwai.nclientv3.files.LocalDownloadState;
import com.maxwai.nclientv3.settings.Global;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Base64;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class DownloadLifecycleTest {
    private Context context;
    private DatabaseHelper database;
    private File root;

    @Before
    public void setUp() throws IOException {
        context = RuntimeEnvironment.getApplication();
        context.deleteDatabase("Entries.db");
        database = new DatabaseHelper(context);
        database.getWritableDatabase();
        Global.initFromShared(context);
        root = Files.createTempDirectory(context.getCacheDir().toPath(), "download-test").toFile();
        Global.DOWNLOADFOLDER = root;
    }

    @After
    public void tearDown() {
        database.close();
        Global.client = null;
        Global.recursiveDelete(root);
        Global.DOWNLOADFOLDER = null;
    }

    @Test
    public void longRateLimitStopsMetadataWithoutRepeatedRequests() {
        AtomicInteger calls = new AtomicInteger();
        Global.client = new OkHttpClient.Builder().addInterceptor(chain -> {
            calls.incrementAndGet();
            return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(429).message("Rate limited").header("Retry-After", "60")
                .body(ResponseBody.create(null, "{}")).build();
        }).build();
        GalleryDownloaderV2 downloader = new GalleryDownloaderV2(context, "Test", null, 123);
        downloader.runDownload();
        assertEquals(1, calls.get());
        assertEquals(GalleryDownloaderV2.Status.FAILED, downloader.getStatus());
        assertEquals(DownloadFailure.RATE_LIMIT, downloader.getFailure());
        assertFalse(downloader.canBeFetched());
    }

    @Test
    public void pauseCancelsMetadataAndImmediateResumeIsRemembered() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch canceled = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Global.client = new OkHttpClient.Builder().addInterceptor(chain -> {
            started.countDown();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!chain.call().isCanceled() && System.nanoTime() < deadline) Thread.yield();
            if (chain.call().isCanceled()) canceled.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("Request timed out");
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            throw new IOException("Canceled test request");
        }).build();
        GalleryDownloaderV2 downloader = new GalleryDownloaderV2(context, "Test", null, 123);
        Thread worker = new Thread(downloader::runDownload);
        worker.start();
        try {
            assertTrue(started.await(5, TimeUnit.SECONDS));
            downloader.setStatus(GalleryDownloaderV2.Status.PAUSED);
            assertTrue(canceled.await(5, TimeUnit.SECONDS));
            downloader.retry();
        } finally {
            release.countDown();
            worker.join(5000);
        }
        assertFalse(worker.isAlive());
        assertEquals(GalleryDownloaderV2.Status.NOT_STARTED, downloader.getStatus());
        assertTrue(downloader.canBeFetched());
    }

    @Test
    public void stoppingKeepsOrDeletesFilesWithoutRemovingPersonalState() throws Exception {
        Gallery gallery = new Gallery(null, "{\"id\":123,\"num_pages\":1,\"title\":{\"pretty\":\"Test gallery\"},"
            + "\"cover\":{\"path\":\"galleries/1/cover.png\"},\"thumbnail\":{\"path\":\"galleries/1/thumb.png\"},"
            + "\"pages\":[{\"path\":\"galleries/1/1.png\",\"width\":1,\"height\":1}]}", null, false);
        Queries.ResumeTable.insert(123, 1);
        Queries.ReadingProgressTable.insert(123, new ReadingPosition(1, 0));
        Queries.FavoriteTable.addFavorite(gallery);
        for (boolean delete : new boolean[]{false, true}) {
            GalleryDownloaderV2 downloader = new GalleryDownloaderV2(context, gallery, 0, 0);
            downloader.initDownload();
            File page = new File(downloader.getFolder(), "001.png");
            Files.write(page.toPath(), new byte[]{1, 2, 3});
            CountDownLatch stopped = new CountDownLatch(1);
            downloader.addObserver(new DownloadObserver() {
                @Override public void triggerStartDownload(GalleryDownloaderV2 ignored) {}
                @Override public void triggerUpdateProgress(GalleryDownloaderV2 ignored, int reach, int total) {}
                @Override public void triggerEndDownload(GalleryDownloaderV2 ignored) {}
                @Override public void triggerPauseDownload(GalleryDownloaderV2 ignored) {}
                @Override public void triggerCancelDownload(GalleryDownloaderV2 ignored) { stopped.countDown(); }
            });
            downloader.cancel(delete);
            assertTrue(stopped.await(5, TimeUnit.SECONDS));
            assertEquals(!delete, page.exists());
            assertTrue(Queries.FavoriteTable.isFavorite(123));
            assertEquals(1, Queries.ResumeTable.pageFromId(123));
            assertNotNull(Queries.ReadingProgressTable.fromId(123));
        }
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void downloadMetadataPreservesExpectedPagesForLocalVerification() throws Exception {
        Gallery gallery = new Gallery(null, "{\"id\":125,\"num_pages\":2,\"title\":{\"pretty\":\"Metadata test\"},"
            + "\"cover\":{\"path\":\"galleries/1/cover.png\"},\"thumbnail\":{\"path\":\"galleries/1/thumb.png\"},"
            + "\"pages\":[{\"path\":\"galleries/1/1.png\",\"width\":1,\"height\":1},"
            + "{\"path\":\"galleries/1/2.png\",\"width\":1,\"height\":1}]}", null, false);
        GalleryDownloaderV2 downloader = new GalleryDownloaderV2(context, gallery, 0, 1);
        downloader.initDownload();
        byte[] image = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a9ZkAAAAASUVORK5CYII=");
        Files.write(new File(downloader.getFolder(), "001.png").toPath(), image);
        assertEquals(2, new LocalGallery(downloader.getFolder()).getPageCount());
        assertEquals(LocalDownloadState.PARTIAL, LocalDownloadState.inspect(downloader.getFolder()));
        Files.write(new File(downloader.getFolder(), "002.png").toPath(), image);
        assertEquals(LocalDownloadState.COMPLETE, LocalDownloadState.inspect(downloader.getFolder()));
    }

    @Test
    public void conflictingRangeIsRejectedWithoutChangingTheQueuedTask() throws Exception {
        Gallery gallery = new Gallery(null, "{\"id\":124,\"num_pages\":10,\"title\":{\"pretty\":\"Range test\"},"
            + "\"cover\":{\"path\":\"galleries/1/cover.png\"},\"thumbnail\":{\"path\":\"galleries/1/thumb.png\"},"
            + "\"pages\":[{\"path\":\"galleries/1/1.png\",\"width\":1,\"height\":1}]}", null, false);
        GalleryDownloaderManager first = new GalleryDownloaderManager(context, gallery, 0, 4);
        try {
            assertTrue(DownloadQueue.add(first));
            assertFalse(DownloadQueue.add(new GalleryDownloaderManager(context, gallery, 5, 9)));
            assertEquals(0, first.downloader().getStart());
            assertEquals(4, first.downloader().getEnd());
            assertSame(first, DownloadQueue.fetch());
        } finally {
            DownloadQueue.remove(first.downloader(), false);
        }
    }
}
