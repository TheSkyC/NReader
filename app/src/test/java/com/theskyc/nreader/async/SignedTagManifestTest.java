package com.theskyc.nreader.async;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import android.app.Application;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class SignedTagManifestTest {
    static String fixture(String kind) throws IOException {
        try (InputStream input = SignedTagManifestTest.class.getResourceAsStream("/signed-feed/" + kind + ".json")) {
            if (input == null) throw new IOException("Missing signed feed fixture");
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test public void verifiesPublisherFixturesUsingPinnedKey() throws Exception {
        byte[] catalog = SignedTagManifest.verify(fixture("catalog").getBytes(StandardCharsets.UTF_8),
            "catalog", 4096, SignedTagManifest.publicKey());
        byte[] translation = SignedTagManifest.verify(fixture("translation").getBytes(StandardCharsets.UTF_8),
            "translation", 32768, SignedTagManifest.publicKey());
        assertEquals(43, com.theskyc.nreader.async.database.TagCatalogManifest.parse(catalog).revision);
        org.junit.Assert.assertTrue(com.theskyc.nreader.async.database.TagTranslationManifest.parse(translation)
            .getPacks().containsKey("zh-Hans"));
    }
    @Test public void verifiesExactBytesAndRejectsTamperingAndWrongPurpose() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair key = generator.generateKeyPair();
        KeyPair other = generator.generateKeyPair();
        byte[] manifest = "{\"schemaVersion\":1}".getBytes(StandardCharsets.UTF_8);
        Signature signer = Signature.getInstance("SHA256withECDSA");
        signer.initSign(key.getPrivate());
        signer.update("NReader tag catalog manifest v2\n".getBytes(StandardCharsets.UTF_8));
        signer.update(manifest);
        String data = Base64.getEncoder().encodeToString(manifest);
        String signature = Base64.getEncoder().encodeToString(signer.sign());
        byte[] valid = envelope(data, signature);
        assertArrayEquals(manifest, SignedTagManifest.verify(valid, "catalog", 4096, key.getPublic()));
        assertThrows(IOException.class, () -> SignedTagManifest.verify(valid, "translation", 4096, key.getPublic()));
        assertThrows(IOException.class, () -> SignedTagManifest.verify(valid, "catalog", 4096, other.getPublic()));
        assertThrows(IOException.class, () -> SignedTagManifest.verify(valid, "catalog", 3, key.getPublic()));
        assertThrows(IOException.class, () -> SignedTagManifest.verify(envelope(data + "A", signature),
            "catalog", 4096, key.getPublic()));
        byte[] altered = Base64.getEncoder().encodeToString("different".getBytes(StandardCharsets.UTF_8))
            .getBytes(StandardCharsets.US_ASCII);
        assertThrows(IOException.class, () -> SignedTagManifest.verify(envelope(new String(altered, StandardCharsets.US_ASCII), signature),
            "catalog", 4096, key.getPublic()));
        byte[] duplicate = ("{\"schemaVersion\":2,\"schemaVersion\":2,\"manifest\":\"" + data
            + "\",\"signature\":\"" + signature + "\"}").getBytes(StandardCharsets.UTF_8);
        assertThrows(IOException.class, () -> SignedTagManifest.verify(duplicate, "catalog", 4096, key.getPublic()));
        assertThrows(IOException.class, () -> SignedTagManifest.verify("{}".getBytes(StandardCharsets.UTF_8),
            "catalog", 4096, key.getPublic()));
    }

    private static byte[] envelope(String manifest, String signature) {
        return ("{\"schemaVersion\":2,\"manifest\":\"" + manifest + "\",\"signature\":\"" + signature + "\"}")
            .getBytes(StandardCharsets.UTF_8);
    }
}
