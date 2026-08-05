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
}
