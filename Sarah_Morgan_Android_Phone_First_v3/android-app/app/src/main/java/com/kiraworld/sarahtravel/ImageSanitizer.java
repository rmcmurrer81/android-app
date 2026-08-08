package com.kiraworld.sarahtravel;

import android.content.ContentResolver;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

public final class ImageSanitizer {
    public static final class Result {
        public final byte[] jpeg;
        public final File file;
        Result(byte[] jpeg, File file) { this.jpeg = jpeg; this.file = file; }
    }

    private ImageSanitizer() { }

    public static Result sanitize(ContentResolver resolver, Uri uri, File directory) throws Exception {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream in = resolver.openInputStream(uri)) { BitmapFactory.decodeStream(in, null, bounds); }
        int sample = 1;
        int max = Math.max(bounds.outWidth, bounds.outHeight);
        while (max / sample > 1600) sample *= 2;
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        Bitmap bitmap;
        try (InputStream in = resolver.openInputStream(uri)) { bitmap = BitmapFactory.decodeStream(in, null, opts); }
        if (bitmap == null) throw new IllegalArgumentException("The selected image could not be read.");
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.JPEG, 88, buffer);
        bitmap.recycle();
        byte[] bytes = buffer.toByteArray();
        directory.mkdirs();
        File file = new File(directory, "trip_photo_" + System.currentTimeMillis() + ".jpg");
        try (FileOutputStream out = new FileOutputStream(file)) { out.write(bytes); }
        return new Result(bytes, file);
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
