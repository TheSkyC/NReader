// NReader modifications by TheSkyC (2026); see NOTICE.
package com.theskyc.nreader.async;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.File;
import java.io.IOException;

import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

@RunWith(RobolectricTestRunner.class)
public class UpdateDownloadTransferTest {
    private static final String ABC_SHA256 = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";
    @Rule public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void validResponseIsWrittenAndVerified() throws Exception {
        File file = new File(temporaryFolder.getRoot(), "valid.part");
        try (Response response = response(200, "abc")) {
            assertTrue(UpdateDownloadTransfer.receive(response, file, 3L, ABC_SHA256, (written, total) -> true));
        }
        assertEquals(3L, file.length());
        assertEquals(ABC_SHA256, UpdateFileVerifier.sha256(file));
    }

    @Test
    public void missingDigestAndOversizeAreRejectedBeforeWriting() throws Exception {
        File file = new File(temporaryFolder.getRoot(), "blocked.part");
        try (Response response = response(200, "abc")) {
            expectFailure(response, file, 3L, null);
        }
        assertFalse(file.exists());
        try (Response response = response(200, "abc")) {
            expectFailure(response, file, VersionChecker.MAX_UPDATE_SIZE_BYTES + 1L, ABC_SHA256);
        }
        assertFalse(file.exists());
    }

    @Test
    public void incorrectSizeDigestAndHttpFailureAreRejected() throws Exception {
        File file = new File(temporaryFolder.getRoot(), "invalid.part");
        try (Response response = response(200, "abc")) {
            expectFailure(response, file, 4L, ABC_SHA256);
        }
        assertFalse(file.exists());
        try (Response response = response(200, "abc")) {
            expectFailure(response, file, 3L, "0000000000000000000000000000000000000000000000000000000000000000");
        }
        try (Response response = response(404, "abc")) {
            expectFailure(response, file, 3L, ABC_SHA256);
        }
    }

    private static void expectFailure(Response response, File file, long size, String digest) throws IOException {
        try {
            UpdateDownloadTransfer.receive(response, file, size, digest, (written, total) -> true);
            fail("Invalid update response must fail verification");
        } catch (IOException expected) {
            // Invalid responses must never be reported as verified.
        }
    }

    private static Response response(int code, String content) {
        return new Response.Builder().request(new Request.Builder().url("https://example.invalid/update.apk").build())
            .protocol(Protocol.HTTP_1_1).code(code).message("test")
            .body(ResponseBody.create(null, content)).build();
    }
}
