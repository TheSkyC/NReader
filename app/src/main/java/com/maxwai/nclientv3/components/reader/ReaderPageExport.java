// NReader modifications by TheSkyC (2026); see NOTICE.
package com.maxwai.nclientv3.components.reader;

import android.content.Context;
import android.graphics.BitmapFactory;
import android.net.Uri;

import androidx.annotation.NonNull;

import com.bumptech.glide.RequestManager;
import com.bumptech.glide.request.FutureTarget;
import com.maxwai.nclientv3.components.GlideX;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class ReaderPageExport {
    private static final String TEMP_SUFFIX = ".part";
    private ReaderPageExport() {}

    public static final class Result {
        public final File file;
        public final String mimeType;

        private Result(File file, String mimeType) {
            this.file = file;
            this.mimeType = mimeType;
        }
    }

    @NonNull
    public static Result prepare(@NonNull Context context, @NonNull Object source,
                                 @NonNull File directory, @NonNull String name, boolean unique)
        throws IOException, InterruptedException {
        RequestManager glide = null;
        FutureTarget<File> target = null;
        try {
            File input;
            if (source instanceof File) input = (File) source;
            else if (source instanceof Uri && "file".equals(((Uri) source).getScheme()))
                input = new File(((Uri) source).getPath());
            else {
                glide = GlideX.with(context.getApplicationContext());
                if (glide == null) throw new IOException("Image loader unavailable");
                target = glide.downloadOnly().load(source).submit();
                try {
                    input = target.get(60, TimeUnit.SECONDS);
                } catch (ExecutionException | TimeoutException exception) {
                    throw new IOException("Unable to load image", exception);
                }
            }
            return copy(input, directory, name, unique);
        } finally {
            if (glide != null && target != null) glide.clear(target);
        }
    }

    @NonNull
    static Result copy(@NonNull File input, @NonNull File directory, @NonNull String name, boolean unique)
        throws IOException, InterruptedException {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(input.getAbsolutePath(), options);
        String extension;
        if ("image/jpeg".equals(options.outMimeType)) extension = ".jpg";
        else if ("image/png".equals(options.outMimeType)) extension = ".png";
        else if ("image/gif".equals(options.outMimeType)) extension = ".gif";
        else if ("image/webp".equals(options.outMimeType)) extension = ".webp";
        else throw new IOException("Unsupported image format");
        if (options.outWidth <= 0 || options.outHeight <= 0) throw new IOException("Invalid image");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Unable to create destination");
        File temporary = File.createTempFile("reader-page-", TEMP_SUFFIX, directory);
        boolean moved = false;
        try {
            try (FileInputStream source = new FileInputStream(input);
                 FileOutputStream destination = new FileOutputStream(temporary)) {
                byte[] buffer = new byte[16384];
                int read;
                while ((read = source.read(buffer)) != -1) {
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                    destination.write(buffer, 0, read);
                }
            }
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            String temporaryName = temporary.getName();
            File output = unique ? new File(directory, temporaryName.substring(0, temporaryName.length() - TEMP_SUFFIX.length()) + extension)
                : new File(directory, name + extension);
            if (unique) Files.move(temporary.toPath(), output.toPath());
            else Files.move(temporary.toPath(), output.toPath(), StandardCopyOption.REPLACE_EXISTING);
            moved = true;
            return new Result(output, options.outMimeType);
        } finally {
            if (!moved) temporary.delete();
        }
    }
}
