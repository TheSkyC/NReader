import com.ibm.icu.lang.UCharacter;
import com.ibm.icu.text.Normalizer2;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Locale;

public final class TagUnicodeProbe {
    private static final Normalizer2 NFC = Normalizer2.getNFCInstance();

    private static void expect(String expected, String actual) {
        if (!expected.equals(actual)) throw new AssertionError("Unexpected Unicode result");
    }

    private static void expectKey(String input, String expected) {
        String key = UCharacter.toLowerCase(Locale.ROOT, NFC.normalize(input)).replace('\u03c2', '\u03c3');
        expect(expected, key);
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 1 && "--scalar-digest".equals(args[0])) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (int codePoint = 0; codePoint <= Character.MAX_CODE_POINT; codePoint++) {
                if (codePoint >= Character.MIN_SURROGATE && codePoint <= Character.MAX_SURROGATE) continue;
                String value = new String(Character.toChars(codePoint));
                String normalized = NFC.normalize(value);
                updateDigest(digest, normalized);
                updateDigest(digest, UCharacter.toLowerCase(Locale.ROOT, normalized).replace('\u03c2', '\u03c3'));
            }
            System.out.println(java.util.HexFormat.of().formatHex(digest.digest()));
            return;
        }
        try (InputStream license = TagUnicodeProbe.class.getResourceAsStream("/META-INF/LICENSE-ICU.txt")) {
            if (license == null || !Arrays.equals(Files.readAllBytes(Path.of(args[0])), license.readAllBytes())) {
                throw new AssertionError("ICU license is missing or does not match");
            }
        }
        expect("16.0.0.0", UCharacter.getUnicodeVersion().toString());
        expect("Caf\u00e9", NFC.normalize("Cafe\u0301"));
        expect("\uac00", NFC.normalize("\u1100\u1161"));
        expectKey("\u1c89", "\u1c8a");
        expectKey("\u039f\u03a3", "\u03bf\u03c3");
        expectKey("\u039f\u03a3\u0391", "\u03bf\u03c3\u03b1");
        expectKey("\u0130", "i\u0307");
        expectKey("\ud801\udc00", "\ud801\udc28");
        System.out.println("Pinned Unicode NFC and search-key samples passed");
    }

    private static void updateDigest(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        int length = bytes.length;
        digest.update((byte) (length >>> 24));
        digest.update((byte) (length >>> 16));
        digest.update((byte) (length >>> 8));
        digest.update((byte) length);
        digest.update(bytes);
    }
}
