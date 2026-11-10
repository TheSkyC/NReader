// NReader modifications by TheSkyC (2026); see NOTICE.
package com.maxwai.nclientv3.files;

import android.app.Application;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.nio.file.Files;
import java.util.Base64;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class DownloadIntegrityTest {
    @Test
    public void scanReportsExactMissingAndDamagedPages() throws Exception {
        File folder = Files.createTempDirectory(RuntimeEnvironment.getApplication().getCacheDir().toPath(), "integrity").toFile();
        try {
            Files.write(new File(folder, ".nomedia").toPath(), "{\"num_pages\":3}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            Files.write(new File(folder, "001.png").toPath(), Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a9ZkAAAAASUVORK5CYII="));
            Files.write(new File(folder, "002.png").toPath(), new byte[0]);
            DownloadIntegrity.Result result = DownloadIntegrity.inspect(folder);
            assertEquals(LocalDownloadState.DAMAGED, result.state);
            assertEquals(java.util.Collections.singletonList(3), result.missing);
            assertEquals(java.util.Collections.singletonList(2), result.damaged);
            assertEquals(3, result.expectedPages);
            assertTrue(result.needsRepair());
        } finally {
            com.maxwai.nclientv3.settings.Global.recursiveDelete(folder);
        }
    }
}
