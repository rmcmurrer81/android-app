package com.kiraworld.sarahtravel;

import android.content.ContentResolver;
import android.net.Uri;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.UUID;

/** One bounded, fsynced, read-only snapshot of an untrusted content provider stream. */
public final class PrivateContentSnapshot implements AutoCloseable {
    public static final int MAX_IMAGE_BYTES = 12_000_000;

    private final File allowedRoot;
    private final File snapshot;
    private final String approvedMimeType;
    private boolean closed;

    private PrivateContentSnapshot(
            File allowedRoot,
            File snapshot,
            String approvedMimeType) {
        this.allowedRoot = allowedRoot;
        this.snapshot = snapshot;
        this.approvedMimeType = approvedMimeType;
    }

    public static PrivateContentSnapshot capture(
            ContentResolver resolver,
            Uri uri,
            File allowedRoot,
            File stagingDirectory,
            int maximumBytes,
            String namePrefix,
            String resolverMimeType) throws Exception {
        if (resolver == null
                || uri == null
                || !"content".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalArgumentException("Only a private Android content share is accepted");
        }
        if (maximumBytes < 1 || maximumBytes > MAX_IMAGE_BYTES) {
            throw new IllegalArgumentException("Private snapshot byte limit was rejected");
        }
        String approvedMime = normalizeApprovedImageMime(resolverMimeType);
        if (approvedMime.isEmpty()) {
            throw new IllegalArgumentException("The resolved image type was rejected");
        }

        File exactRoot = allowedRoot.getCanonicalFile();
        File exactDirectory = stagingDirectory.getCanonicalFile();
        if (!insideOrEqual(exactRoot, exactDirectory)) {
            throw new IllegalStateException("Private snapshot staging scope was rejected");
        }
        if (!exactDirectory.exists() && !exactDirectory.mkdirs()) {
            throw new IllegalStateException("Private snapshot staging directory is unavailable");
        }
        if (!exactDirectory.isDirectory()) {
            throw new IllegalStateException("Private snapshot staging path is not a directory");
        }

        String prefix = namePrefix == null
                ? "content" : namePrefix.replaceAll("[^A-Za-z0-9_-]", "");
        if (prefix.isEmpty()) prefix = "content";
        File target = new File(
                exactDirectory,
                "." + prefix + "_snapshot_" + UUID.randomUUID() + ".bin")
                .getCanonicalFile();
        if (target.getParentFile() == null
                || !target.getParentFile().getCanonicalFile().equals(exactDirectory)
                || target.exists()
                || !target.createNewFile()) {
            throw new IllegalStateException("Private snapshot target was rejected");
        }

        boolean complete = false;
        try {
            int total = 0;
            byte[] buffer = new byte[8192];
            // This is the one and only provider-stream open. All decoding uses
            // the exact closed, fsynced snapshot below.
            try (InputStream input = resolver.openInputStream(uri);
                 FileOutputStream output = new FileOutputStream(target, false)) {
                if (input == null) {
                    throw new IllegalArgumentException("The selected image could not be read");
                }
                int count;
                while ((count = input.read(buffer)) != -1) {
                    total += count;
                    if (total > maximumBytes) {
                        throw new IllegalArgumentException("The selected image exceeds 12 MB");
                    }
                    output.write(buffer, 0, count);
                }
                output.flush();
                output.getFD().sync();
            }
            if (total < 1 || target.length() != total) {
                throw new IllegalArgumentException("The selected image snapshot is empty or incomplete");
            }
            if (!target.setReadOnly() || target.canWrite()) {
                throw new IllegalStateException("Private image snapshot could not be sealed read-only");
            }
            complete = true;
            return new PrivateContentSnapshot(exactRoot, target, approvedMime);
        } finally {
            if (!complete && target.exists()) {
                target.setWritable(true, true);
                if (!target.delete() || target.exists()) {
                    throw new IllegalStateException(
                            "Incomplete private snapshot cleanup failed at "
                                    + target.getCanonicalPath());
                }
            }
        }
    }

    public File file() throws Exception {
        if (closed) throw new IllegalStateException("Private snapshot is already closed");
        File exact = snapshot.getCanonicalFile();
        if (!insideOrEqual(allowedRoot, exact)
                || !exact.isFile()
                || exact.length() < 1
                || exact.canWrite()) {
            throw new IllegalStateException("Private snapshot integrity boundary failed");
        }
        return exact;
    }

    public String approvedMimeType() {
        return approvedMimeType;
    }

    public static String normalizeApprovedImageMime(String value) {
        String mime = value == null
                ? "" : value.trim().toLowerCase(java.util.Locale.US);
        return "image/jpeg".equals(mime)
                || "image/png".equals(mime)
                || "image/webp".equals(mime)
                ? mime : "";
    }

    @Override public void close() throws Exception {
        if (closed) return;
        closed = true;
        File exact = snapshot.getCanonicalFile();
        if (!insideOrEqual(allowedRoot, exact) || !exact.isFile()) {
            throw new IllegalStateException("Private snapshot cleanup scope was rejected");
        }
        exact.setWritable(true, true);
        if (!exact.delete() || exact.exists()) {
            throw new IllegalStateException(
                    "Private snapshot cleanup failed at " + exact.getCanonicalPath());
        }
    }

    private static boolean insideOrEqual(File root, File candidate) throws Exception {
        File exactRoot = root.getCanonicalFile();
        File exactCandidate = candidate.getCanonicalFile();
        return exactCandidate.equals(exactRoot)
                || exactCandidate.getCanonicalPath().startsWith(
                        exactRoot.getCanonicalPath() + File.separator);
    }
}
