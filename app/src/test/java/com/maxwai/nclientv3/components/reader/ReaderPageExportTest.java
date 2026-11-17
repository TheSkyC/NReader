// NReader modifications by TheSkyC (2026); see NOTICE.
package com.maxwai.nclientv3.components.reader;

import android.app.Application;
import android.graphics.Bitmap;
import android.net.Uri;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.rules.TemporaryFolder;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class ReaderPageExportTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void copiesOriginalBytesAndUsesDetectedFormat() throws Exception {
        File source = temporary.newFile("source.bin");
        try (FileOutputStream output = new FileOutputStream(source)) {
            assertTrue(Bitmap.createBitmap(8, 12, Bitmap.Config.ARGB_8888)
                .compress(Bitmap.CompressFormat.PNG, 100, output));
        }
        byte[] png = Files.readAllBytes(source.toPath());
        File destination = temporary.newFolder("output");
        ReaderPageExport.Result result = ReaderPageExport.copy(source, destination, "page", false);
        assertEquals("image/png", result.mimeType);
        assertEquals("page.png", result.file.getName());
        assertArrayEquals(png, Files.readAllBytes(result.file.toPath()));

        ReaderPageExport.Result shared = ReaderPageExport.copy(source, destination, "ignored", true);
        assertFalse(result.file.equals(shared.file));
        assertArrayEquals(png, Files.readAllBytes(shared.file.toPath()));
    }

    @Test
    public void rejectsInvalidImagesWithoutCreatingOutput() throws Exception {
        File source = temporary.newFile("invalid.jpg");
        Files.write(source.toPath(), new byte[]{1, 2, 3});
        File destination = temporary.newFolder("output");
        assertThrows(IOException.class, () -> ReaderPageExport.copy(source, destination, "page", false));
        assertTrue(destination.list().length == 0);
    }

    @Test
    public void interruptedCopyKeepsExistingFileAndRemovesTemporaryFile() throws Exception {
        File source = temporary.newFile("valid.png");
        try (FileOutputStream output = new FileOutputStream(source)) {
            assertTrue(Bitmap.createBitmap(8, 12, Bitmap.Config.ARGB_8888)
                .compress(Bitmap.CompressFormat.PNG, 100, output));
        }
        File destination = temporary.newFolder("existing-output");
        File existing = new File(destination, "page.png");
        byte[] original = {1, 2, 3};
        Files.write(existing.toPath(), original);
        try {
            Thread.currentThread().interrupt();
            assertThrows(InterruptedException.class, () -> ReaderPageExport.copy(source, destination, "page", false));
        } finally {
            Thread.interrupted();
        }
        assertArrayEquals(original, Files.readAllBytes(existing.toPath()));
        assertEquals(1, destination.list().length);
    }

    @Test
    public void downloadsAndCopiesOnlineSourceBytes() throws Exception {
        File source = temporary.newFile("online.png");
        try (FileOutputStream output = new FileOutputStream(source)) {
            assertTrue(Bitmap.createBitmap(8, 12, Bitmap.Config.ARGB_8888)
                .compress(Bitmap.CompressFormat.PNG, 100, output));
        }
        byte[] png = Files.readAllBytes(source.toPath());
        File destination = temporary.newFolder("online-output");
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try (ServerSocket server = new ServerSocket(0)) {
            server.setSoTimeout(10000);
            Future<?> response = executor.submit(() -> {
                try (Socket socket = server.accept()) {
                    BufferedReader request = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
                    String line;
                    while ((line = request.readLine()) != null && !line.isEmpty()) {}
                    OutputStream output = socket.getOutputStream();
                    output.write(("HTTP/1.1 200 OK\r\nContent-Type: image/png\r\nContent-Length: "
                        + png.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                    output.write(png);
                    output.flush();
                } catch (IOException exception) {
                    throw new RuntimeException(exception);
                }
            });
            Future<ReaderPageExport.Result> exported = executor.submit(() -> ReaderPageExport.prepare(
                RuntimeEnvironment.getApplication(), Uri.parse("http://127.0.0.1:" + server.getLocalPort() + "/page.png"),
                destination, "online", false));
            ReaderPageExport.Result result = exported.get(20, TimeUnit.SECONDS);
            response.get(10, TimeUnit.SECONDS);
            assertEquals("image/png", result.mimeType);
            assertArrayEquals(png, Files.readAllBytes(result.file.toPath()));
        } finally {
            executor.shutdownNow();
        }
    }
}
