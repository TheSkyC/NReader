// NReader modifications by TheSkyC (2026); see NOTICE.
package com.theskyc.nreader.components.reader;

import androidx.annotation.NonNull;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.function.BooleanSupplier;
import java.util.zip.CRC32;

/** Identifies static PNGs whose untransformed pixel geometry is unambiguous. */
final class ContinuousTileSourceEligibility {
    private static final long SIGNATURE = 0x89504e470d0a1a0aL;
    private static final int IHDR = 0x49484452;
    private static final int IDAT = 0x49444154;
    private static final int IEND = 0x49454e44;
    private static final int ACTL = 0x6163544c;
    private static final int EXIF = 0x65584966;
    private static final long MAX_HEADER_BYTES = 1024 * 1024;
    private static final int MAX_CHUNKS = 16384;

    private ContinuousTileSourceEligibility() {}

    static boolean isEligible(@NonNull File file) {
        return isEligible(file, () -> false);
    }

    static boolean isEligible(@NonNull File file, @NonNull BooleanSupplier canceled) {
        try (DataInputStream input = new DataInputStream(new BufferedInputStream(new FileInputStream(file)))) {
            if (input.readLong() != SIGNATURE) return false;
            long consumed = 8;
            long metadataBytes = 8;
            boolean imageData = false;
            byte[] buffer = new byte[64 * 1024];
            for (int index = 0; index < MAX_CHUNKS; index++) {
                if (Thread.currentThread().isInterrupted() || canceled.getAsBoolean()) return false;
                long length = Integer.toUnsignedLong(input.readInt());
                int type = input.readInt();
                consumed += 8;
                if (index == 0 && (type != IHDR || length != 13)) return false;
                if (index > 0 && type == IHDR) return false;
                if (type == ACTL || type == EXIF) return false;
                if (length > file.length() - consumed - 4) return false;
                if (type == IEND && (!imageData || length != 0)) return false;
                if (type == IDAT) {
                    imageData |= length > 0;
                } else {
                    metadataBytes += length + 12;
                    if (metadataBytes > MAX_HEADER_BYTES) return false;
                }
                CRC32 crc = new CRC32();
                crc.update(type >>> 24);
                crc.update(type >>> 16);
                crc.update(type >>> 8);
                crc.update(type);
                long remaining = length;
                while (remaining > 0) {
                    if (Thread.currentThread().isInterrupted() || canceled.getAsBoolean()) return false;
                    int count = (int) Math.min(remaining, buffer.length);
                    input.readFully(buffer, 0, count);
                    crc.update(buffer, 0, count);
                    remaining -= count;
                }
                if (crc.getValue() != Integer.toUnsignedLong(input.readInt())) return false;
                if (type == IEND) return true;
                consumed += length + 4;
            }
        } catch (IOException | SecurityException exception) {
            return false;
        }
        return false;
    }

}
