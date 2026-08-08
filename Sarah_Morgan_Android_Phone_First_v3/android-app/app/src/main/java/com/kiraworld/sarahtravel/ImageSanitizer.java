package com.kiraworld.sarahtravel;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.util.UUID;

public final class ImageSanitizer {
    private static final long MAX_SOURCE_PIXELS = 100_000_000L;
    private static final long MAX_DECODE_PIXELS = 3_000_000L;
    public static final class Result {
        public final byte[] jpeg;
        public final File file;
        Result(byte[] jpeg, File file) { this.jpeg = jpeg; this.file = file; }
    }

    private ImageSanitizer() { }

    /** Decode only one already-fsynced, read-only private snapshot. */
    public static Result sanitize(
            File snapshot,
            File directory,
            String approvedResolverMimeType) throws Exception {
        File exactDirectory = directory.getCanonicalFile();
        File exactSnapshot = snapshot.getCanonicalFile();
        if (!exactDirectory.isDirectory()
                || exactSnapshot.getParentFile() == null
                || !exactSnapshot.getParentFile().getCanonicalFile().equals(exactDirectory)
                || !exactSnapshot.isFile()
                || exactSnapshot.length() < 1
                || exactSnapshot.length() > PrivateContentSnapshot.MAX_IMAGE_BYTES
                || exactSnapshot.canWrite()) {
            throw new IllegalArgumentException("The private image snapshot boundary was rejected.");
        }
        // Read the exact sealed file once. Both bounds and pixel decoding use
        // this same immutable byte array; no content URI is reopened.
        byte[] source = Files.readAllBytes(exactSnapshot.toPath());
        if (source.length < 1
                || source.length != exactSnapshot.length()
                || source.length > PrivateContentSnapshot.MAX_IMAGE_BYTES) {
            throw new IllegalArgumentException("The private image snapshot changed while reading.");
        }
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(source, 0, source.length, bounds);
        String approvedMime = PrivateContentSnapshot.normalizeApprovedImageMime(
                approvedResolverMimeType);
        String decodedMime = PrivateContentSnapshot.normalizeApprovedImageMime(
                bounds.outMimeType);
        if (bounds.outWidth < 1
                || bounds.outHeight < 1
                || bounds.outWidth > 32_768
                || bounds.outHeight > 32_768
                || (long) bounds.outWidth * (long) bounds.outHeight > MAX_SOURCE_PIXELS) {
            throw new IllegalArgumentException("The selected image dimensions were rejected.");
        }
        if (approvedMime.isEmpty() || !approvedMime.equals(decodedMime)) {
            throw new IllegalArgumentException(
                    "The selected image pixels do not match the approved content type.");
        }
        int sample = 1;
        int max = Math.max(bounds.outWidth, bounds.outHeight);
        while (max / sample > 1600) sample *= 2;
        long sampledWidth = (bounds.outWidth + (long) sample - 1L) / sample;
        long sampledHeight = (bounds.outHeight + (long) sample - 1L) / sample;
        if (sampledWidth * sampledHeight > MAX_DECODE_PIXELS) {
            throw new IllegalArgumentException("The selected image decode allocation was rejected.");
        }
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        Bitmap bitmap = BitmapFactory.decodeByteArray(source, 0, source.length, opts);
        if (bitmap == null) throw new IllegalArgumentException("The selected image could not be read.");
        if ((long) bitmap.getWidth() * (long) bitmap.getHeight() > MAX_DECODE_PIXELS) {
            bitmap.recycle();
            throw new IllegalArgumentException("The decoded image exceeded its pixel limit.");
        }
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try {
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 88, buffer)) {
                throw new IllegalArgumentException("The selected image could not be sanitized.");
            }
        } finally {
            bitmap.recycle();
        }
        byte[] bytes = buffer.toByteArray();
        if (bytes.length < 1) {
            throw new IllegalArgumentException("The sanitized image was empty.");
        }
        File file = new File(
                exactDirectory, "sanitized_image_" + UUID.randomUUID() + ".jpg")
                .getCanonicalFile();
        if (file.getParentFile() == null
                || !file.getParentFile().getCanonicalFile().equals(exactDirectory)
                || file.exists()
                || !file.createNewFile()) {
            throw new IllegalStateException("Sanitized image target was rejected.");
        }
        boolean complete = false;
        try {
            try (FileOutputStream out = new FileOutputStream(file, false)) {
                out.write(bytes);
                out.flush();
                out.getFD().sync();
            }
            if (file.length() != bytes.length) {
                throw new IllegalStateException("Sanitized image write was incomplete.");
            }
            complete = true;
            return new Result(bytes, file);
        } finally {
            if (!complete && file.exists() && (!file.delete() || file.exists())) {
                throw new IllegalStateException(
                        "Incomplete sanitized image cleanup failed at "
                                + file.getCanonicalPath());
            }
        }
    }

    /** Re-decodes pixels and re-encodes a bounded JPEG; EXIF and location metadata are not copied. */
    public static byte[] syncDerivative(File source) throws Exception {
        if (source == null || !source.isFile()) {
            throw new IllegalArgumentException("Sync photo source is unavailable");
        }
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(source.getAbsolutePath(), bounds);
        if (bounds.outWidth < 1 || bounds.outHeight < 1) {
            throw new IllegalArgumentException("Sync photo pixels could not be decoded");
        }
        int sample = 1;
        while (Math.max(bounds.outWidth, bounds.outHeight) / sample
                > SyncPhotoPolicy.MAX_DIMENSION * 2) sample *= 2;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        Bitmap decoded = BitmapFactory.decodeFile(source.getAbsolutePath(), options);
        if (decoded == null) throw new IllegalArgumentException("Sync photo pixels could not be decoded");
        Bitmap output = decoded;
        int max = Math.max(decoded.getWidth(), decoded.getHeight());
        if (max > SyncPhotoPolicy.MAX_DIMENSION) {
            float scale = SyncPhotoPolicy.MAX_DIMENSION / (float) max;
            output = Bitmap.createScaledBitmap(
                    decoded,
                    Math.max(1, Math.round(decoded.getWidth() * scale)),
                    Math.max(1, Math.round(decoded.getHeight() * scale)),
                    true);
        }
        try {
            for (int quality : new int[]{82, 72, 62, 52}) {
                ByteArrayOutputStream encoded = new ByteArrayOutputStream();
                if (!output.compress(Bitmap.CompressFormat.JPEG, quality, encoded)) continue;
                byte[] bytes = encoded.toByteArray();
                if (bytes.length <= SyncPhotoPolicy.MAX_DERIVATIVE_BYTES) return bytes;
            }
            throw new IllegalArgumentException("Sanitized sync derivative exceeded the size limit");
        } finally {
            if (output != decoded) output.recycle();
            decoded.recycle();
        }
    }
}
