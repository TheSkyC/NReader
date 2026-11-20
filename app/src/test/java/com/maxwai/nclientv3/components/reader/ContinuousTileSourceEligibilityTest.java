// NReader modifications by TheSkyC (2026); see NOTICE.
package com.maxwai.nclientv3.components.reader;

import android.app.Application;
import android.graphics.Bitmap;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.CRC32;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class ContinuousTileSourceEligibilityTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void acceptsRealPngAndRejectsOtherAndTruncatedFiles() throws Exception {
        File png = folder.newFile("page.png");
        Bitmap bitmap = Bitmap.createBitmap(8, 16, Bitmap.Config.ARGB_8888);
        try (FileOutputStream stream = new FileOutputStream(png)) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream));
        }
        bitmap.recycle();
        assertTrue(ContinuousTileSourceEligibility.isEligible(png));
        assertFalse(ContinuousTileSourceEligibility.isEligible(folder.newFile("empty.png")));
        try (DataOutputStream stream = new DataOutputStream(new FileOutputStream(png))) {
            stream.writeLong(0x89504e470d0a1a0aL);
            stream.writeInt(13);
            stream.writeInt(0x49484452);
        }
        assertFalse(ContinuousTileSourceEligibility.isEligible(png));
    }

    @Test
    public void rejectsCorruptedImageDataEvenWhenChunkLengthsAreValid() throws Exception {
        File png = folder.newFile("corrupted.png");
        Bitmap bitmap = Bitmap.createBitmap(8, 16, Bitmap.Config.ARGB_8888);
        try (FileOutputStream stream = new FileOutputStream(png)) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream));
        }
        bitmap.recycle();
        assertTrue(ContinuousTileSourceEligibility.isEligible(png));
        try (RandomAccessFile file = new RandomAccessFile(png, "rw")) {
            file.seek(8);
            while (true) {
                int length = file.readInt();
                int type = file.readInt();
                if (type == 0x49444154) {
                    assertTrue(length > 0);
                    long position = file.getFilePointer();
                    int value = file.readUnsignedByte();
                    file.seek(position);
                    file.writeByte(value ^ 1);
                    break;
                }
                file.seek(file.getFilePointer() + length + 4);
            }
        }
        assertFalse(ContinuousTileSourceEligibility.isEligible(png));
    }

    @Test
    public void rejectsAnimationOrientationAndOversizedMetadata() throws Exception {
        assertFalse(ContinuousTileSourceEligibility.isEligible(headerWithChunk(0x6163544c, 8)));
        assertFalse(ContinuousTileSourceEligibility.isEligible(headerWithChunk(0x65584966, 4)));
        assertFalse(ContinuousTileSourceEligibility.isEligible(headerWithChunk(0x74455874, 1024 * 1024)));
    }

    @Test
    public void rejectsOrientationMetadataAfterImageData() throws Exception {
        File file = folder.newFile();
        try (DataOutputStream output = new DataOutputStream(new FileOutputStream(file))) {
            output.writeLong(0x89504e470d0a1a0aL);
            writeChunk(output, 0x49484452, new byte[13]);
            writeChunk(output, 0x49444154, new byte[1]);
            writeChunk(output, 0x65584966, new byte[4]);
            writeChunk(output, 0x49454e44, new byte[0]);
        }
        assertFalse(ContinuousTileSourceEligibility.isEligible(file));
    }

    @Test
    public void acceptsPngWithManyImageDataChunks() throws Exception {
        File png = folder.newFile("many-chunks.png");
        try (DataOutputStream output = new DataOutputStream(new FileOutputStream(png))) {
            output.writeLong(0x89504e470d0a1a0aL);
            writeChunk(output, 0x49484452, new byte[13]);
            for (int index = 0; index < 3073; index++) writeChunk(output, 0x49444154, new byte[1]);
            writeChunk(output, 0x49454e44, new byte[0]);
        }
        assertTrue(ContinuousTileSourceEligibility.isEligible(png));
    }

    @Test
    public void cancellationStopsPngInspection() throws Exception {
        File png = folder.newFile("cancel.png");
        Bitmap bitmap = Bitmap.createBitmap(8, 16, Bitmap.Config.ARGB_8888);
        try (FileOutputStream stream = new FileOutputStream(png)) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream));
        }
        bitmap.recycle();
        AtomicInteger checks = new AtomicInteger();
        assertFalse(ContinuousTileSourceEligibility.isEligible(png, () -> checks.incrementAndGet() >= 2));
        assertTrue(checks.get() >= 2);
    }

    private File headerWithChunk(int type, int length) throws Exception {
        File file = folder.newFile();
        try (DataOutputStream output = new DataOutputStream(new FileOutputStream(file))) {
            output.writeLong(0x89504e470d0a1a0aL);
            writeChunk(output, 0x49484452, new byte[13]);
            writeChunk(output, type, new byte[length]);
            writeChunk(output, 0x49444154, new byte[1]);
            writeChunk(output, 0x49454e44, new byte[0]);
        }
        return file;
    }

    private static void writeChunk(DataOutputStream output, int type, byte[] data) throws Exception {
        output.writeInt(data.length);
        output.writeInt(type);
        output.write(data);
        CRC32 crc = new CRC32();
        crc.update(type >>> 24);
        crc.update(type >>> 16);
        crc.update(type >>> 8);
        crc.update(type);
        crc.update(data);
        output.writeInt((int) crc.getValue());
    }
}
