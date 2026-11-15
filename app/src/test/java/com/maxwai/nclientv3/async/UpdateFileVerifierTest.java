// NReader modifications by TheSkyC (2026); see NOTICE.
package com.maxwai.nclientv3.async;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

public class UpdateFileVerifierTest {
    @Test
    public void verifiesKnownDigestAndRejectsDifferentDigest() throws Exception {
        File file = File.createTempFile("nreader-digest", ".bin");
        try {
            try (FileOutputStream output = new FileOutputStream(file)) {
                output.write("nreader".getBytes(StandardCharsets.UTF_8));
            }
            String digest = UpdateFileVerifier.sha256(file);
            assertTrue(UpdateFileVerifier.matchesSha256(file, digest));
            assertTrue(UpdateFileVerifier.matchesSha256(file, "sha256:" + digest));
            assertFalse(UpdateFileVerifier.matchesSha256(file, "00" + digest.substring(2)));
        } finally {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
    }
}
