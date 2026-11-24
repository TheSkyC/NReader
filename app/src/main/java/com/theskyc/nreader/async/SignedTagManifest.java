package com.theskyc.nreader.async;

import android.util.JsonReader;
import android.util.JsonToken;

import com.theskyc.nreader.utility.JsonStringGuard;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;

final class SignedTagManifest {
    static final int MAX_BYTES = 65536;
    private static final String PUBLIC_KEY =
        "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE8H2GwUYavL11UtqDdVk7iYNTPcespbX4UisBe+ezvxIP8LsRJlKXrrGOufcNx3c8lzkJYmRvKNCHhpq2rjPZlQ==";

    private SignedTagManifest() {}

    static boolean isSigned(okhttp3.HttpUrl url) {
        return url.encodedPath().endsWith("/manifest-v2.json");
    }

    static PublicKey publicKey() throws IOException {
        try {
            return KeyFactory.getInstance("EC").generatePublic(
                new X509EncodedKeySpec(Base64.getDecoder().decode(PUBLIC_KEY)));
        } catch (GeneralSecurityException | IllegalArgumentException error) {
            throw new IOException("Invalid tag feed public key", error);
        }
    }

    static byte[] verify(byte[] envelope, String kind, int limit, PublicKey key) throws IOException {
        if (envelope.length == 0 || envelope.length > MAX_BYTES) throw new IOException("Invalid signed manifest size");
        String prefix;
        if ("catalog".equals(kind)) prefix = "NReader tag catalog manifest v2\n";
        else if ("translation".equals(kind)) prefix = "NReader tag translation manifest v2\n";
        else throw new IOException("Invalid tag feed kind");
        String manifest = null;
        String signature = null;
        boolean version = false;
        try (JsonReader reader = JsonStringGuard.open(new ByteArrayInputStream(envelope), MAX_BYTES)) {
            Set<String> fields = new HashSet<>();
            reader.beginObject();
            while (reader.hasNext()) {
                String name = reader.nextName();
                if (!fields.add(name)) throw new IOException("Duplicate signed manifest field");
                switch (name) {
                    case "schemaVersion":
                        if (reader.peek() != JsonToken.NUMBER || !"2".equals(reader.nextString())) {
                            throw new IOException("Unsupported signed manifest version");
                        }
                        version = true;
                        break;
                    case "manifest":
                        if (reader.peek() != JsonToken.STRING) throw new IOException("Invalid manifest encoding");
                        manifest = reader.nextString();
                        break;
                    case "signature":
                        if (reader.peek() != JsonToken.STRING) throw new IOException("Invalid signature encoding");
                        signature = reader.nextString();
                        break;
                    default: throw new IOException("Unknown signed manifest field");
                }
            }
            reader.endObject();
            if (!version || manifest == null || signature == null || reader.peek() != JsonToken.END_DOCUMENT) {
                throw new IOException("Invalid signed manifest");
            }
            byte[] bytes = decode(manifest);
            byte[] signed = decode(signature);
            if (bytes.length == 0 || bytes.length > limit || signed.length < 8 || signed.length > 80) {
                throw new IOException("Invalid signed data size");
            }
            Signature verifier = Signature.getInstance("SHA256withECDSA");
            verifier.initVerify(key);
            verifier.update(prefix.getBytes(StandardCharsets.UTF_8));
            verifier.update(bytes);
            if (!verifier.verify(signed)) throw new IOException("Invalid tag feed signature");
            return bytes;
        } catch (GeneralSecurityException | IllegalArgumentException | IllegalStateException error) {
            throw new IOException("Invalid signed tag manifest", error);
        }
    }

    private static byte[] decode(String text) throws IOException {
        byte[] result = Base64.getDecoder().decode(text);
        if (!Base64.getEncoder().encodeToString(result).equals(text)) throw new IOException("Non-canonical Base64");
        return result;
    }
}
