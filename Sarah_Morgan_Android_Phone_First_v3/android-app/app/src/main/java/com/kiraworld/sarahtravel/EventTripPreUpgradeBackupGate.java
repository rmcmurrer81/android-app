package com.kiraworld.sarahtravel;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Preserves the exact R1 event-trip database before SQLiteOpenHelper can run v2.
 * This gate never opens the database and therefore cannot trigger an upgrade.
 */
public final class EventTripPreUpgradeBackupGate {
    private static final String DATABASE = "sarah_event_trips.db";
    private static final String PREFS = "sarah_event_trip_upgrade_backup";
    private static final String READY = "v1_to_v2_backup_ready";

    public static final class Result {
        public final boolean mayOpenV2;
        public final String status;
        public final String manifestPath;
        public final String manifestSha256;

        Result(boolean mayOpenV2, String status, String manifestPath, String manifestSha256) {
            this.mayOpenV2 = mayOpenV2;
            this.status = status;
            this.manifestPath = manifestPath;
            this.manifestSha256 = manifestSha256;
        }
    }

    private EventTripPreUpgradeBackupGate() { }

    public static synchronized Result ensure(Context context) {
        if (context == null) return blocked("CONTEXT_UNAVAILABLE", "", "");
        Context app = context.getApplicationContext();
        File source = app.getDatabasePath(DATABASE);
        if (!source.isFile()) return allowed("NO_EXISTING_EVENT_TRIP_DATABASE", "", "");

        int version;
        try {
            version = readSqliteUserVersion(source);
        } catch (Exception failure) {
            return blocked("DATABASE_VERSION_READ_FAILED", "", "");
        }
        if (EventTripPreUpgradeVersionPolicy.mayOpenV2(version, false)) {
            return allowed("DATABASE_ALREADY_AT_VERSION_2", "", "");
        }
        if (EventTripPreUpgradeVersionPolicy.unexpected(version)) {
            return blocked("UNEXPECTED_DATABASE_VERSION_" + version, "", "");
        }

        SharedPreferences preferences = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        File recoveryRoot = new File(app.getFilesDir(), "recovery/event_trip_v1_pre_v2");
        if (preferences.getBoolean(READY, false)) {
            String manifestPath = preferences.getString("manifest_path", "");
            String expectedHash = preferences.getString("manifest_sha256", "");
            File manifest = new File(manifestPath);
            try {
                if (verifyExistingBackup(recoveryRoot, manifest, expectedHash, source)) {
                    return allowed("VERIFIED_EXISTING_R1_BACKUP", manifestPath, expectedHash);
                }
            } catch (Exception ignored) { }
        }
        File target = new File(recoveryRoot,
                System.currentTimeMillis() + "_" + BuildConfig.VERSION_NAME.replaceAll("[^A-Za-z0-9._-]", "_"));
        if (!target.mkdirs() || !target.isDirectory()) {
            return blocked("BACKUP_DIRECTORY_CREATE_FAILED", "", "");
        }

        try {
            JSONArray files = new JSONArray();
            for (String suffix : new String[]{"", "-wal", "-shm"}) {
                File input = new File(source.getAbsolutePath() + suffix);
                if (!input.isFile()) continue;
                File output = new File(target, DATABASE + suffix);
                String sourceHash = sha256(input);
                long sourceSize = input.length();
                copyAndSync(input, output);
                String outputHash = sha256(output);
                if (sourceSize != output.length() || !sourceHash.equals(outputHash)) {
                    discardExactIncompleteTarget(recoveryRoot, target);
                    return blocked("BACKUP_COPY_VERIFICATION_FAILED", "", "");
                }
                JSONObject row = new JSONObject();
                row.put("name", output.getName());
                row.put("bytes", output.length());
                row.put("sha256", outputHash);
                files.put(row);
            }
            if (files.length() == 0) {
                discardExactIncompleteTarget(recoveryRoot, target);
                return blocked("NO_DATABASE_FILES_COPIED", "", "");
            }

            JSONObject record = new JSONObject();
            record.put("schema_version_before_open", 1);
            record.put("target_schema_version", 2);
            record.put("created_at_utc_epoch_ms", System.currentTimeMillis());
            record.put("app_version", BuildConfig.VERSION_NAME);
            record.put("build_commit", BuildConfig.SARAH_BUILD_COMMIT);
            record.put("status", "R1_PRE_UPGRADE_BACKUP_HASH_VERIFIED");
            record.put("rollback_acceptance", "NOT_TESTED_NOT_CLAIMED");
            record.put("files", files);
            File manifest = new File(target, "MANIFEST.json");
            try (FileOutputStream output = new FileOutputStream(manifest, false)) {
                output.write(record.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                output.getFD().sync();
            }
            String manifestHash = sha256(manifest);
            boolean recorded = preferences.edit()
                    .putBoolean(READY, true)
                    .putString("status", "R1_PRE_UPGRADE_BACKUP_HASH_VERIFIED")
                    .putString("manifest_path", manifest.getAbsolutePath())
                    .putString("manifest_sha256", manifestHash)
                    .commit();
            if (!recorded) {
                discardExactIncompleteTarget(recoveryRoot, target);
                return blocked("BACKUP_STATUS_COMMIT_FAILED", "", "");
            }
            return allowed("R1_PRE_UPGRADE_BACKUP_HASH_VERIFIED",
                    manifest.getAbsolutePath(), manifestHash);
        } catch (Exception failure) {
            discardExactIncompleteTarget(recoveryRoot, target);
            return blocked("R1_PRE_UPGRADE_BACKUP_FAILED", "", "");
        }
    }

    /**
     * Remove only files this one uncommitted attempt can create. Unknown
     * children or any scope mismatch are preserved for manual review.
     */
    private static void discardExactIncompleteTarget(File recoveryRoot, File target) {
        try {
            File exactRoot = recoveryRoot.getCanonicalFile();
            File exactTarget = target.getCanonicalFile();
            if (exactTarget.getParentFile() == null
                    || !exactTarget.getParentFile().getCanonicalFile().equals(exactRoot)
                    || !exactTarget.isDirectory()) return;
            File[] children = exactTarget.listFiles();
            if (children == null) return;
            Set<String> allowed = new HashSet<>();
            allowed.add(DATABASE);
            allowed.add(DATABASE + "-wal");
            allowed.add(DATABASE + "-shm");
            allowed.add("MANIFEST.json");
            for (File child : children) {
                if (!child.isFile() || !allowed.contains(child.getName())) return;
            }
            for (File child : children) {
                if (child.exists() && (!child.delete() || child.exists())) return;
            }
            if (exactTarget.exists()) exactTarget.delete();
        } catch (Exception ignored) {
            // A residual is safer than deleting outside the exact attempt.
        }
    }

    private static int readSqliteUserVersion(File database) throws Exception {
        byte[] header = new byte[64];
        try (InputStream input = new FileInputStream(database)) {
            int offset = 0;
            while (offset < header.length) {
                int read = input.read(header, offset, header.length - offset);
                if (read < 0) break;
                offset += read;
            }
            if (offset < header.length) throw new IllegalStateException("SQLite header is incomplete");
        }
        String signature = new String(header, 0, 16, java.nio.charset.StandardCharsets.US_ASCII);
        if (!"SQLite format 3\u0000".equals(signature)) {
            throw new IllegalStateException("SQLite signature is invalid");
        }
        return ((header[60] & 0xff) << 24)
                | ((header[61] & 0xff) << 16)
                | ((header[62] & 0xff) << 8)
                | (header[63] & 0xff);
    }

    private static boolean verifyExistingBackup(
            File recoveryRoot,
            File manifest,
            String expectedManifestHash,
            File currentDatabase) throws Exception {
        if (!manifest.isFile() || !expectedManifestHash.matches("[a-f0-9]{64}")) return false;
        File canonicalRoot = recoveryRoot.getCanonicalFile();
        File canonicalManifest = manifest.getCanonicalFile();
        if (!canonicalManifest.getPath().startsWith(canonicalRoot.getPath() + File.separator)) {
            return false;
        }
        if (!expectedManifestHash.equals(sha256(canonicalManifest))) return false;
        JSONObject record = readManifest(canonicalManifest);
        if (record.optInt("schema_version_before_open", -1) != 1
                || record.optInt("target_schema_version", -1) != 2
                || !"R1_PRE_UPGRADE_BACKUP_HASH_VERIFIED".equals(record.optString("status", ""))) {
            return false;
        }
        JSONArray files = record.optJSONArray("files");
        if (files == null || files.length() < 1 || files.length() > 3) return false;
        Set<String> seen = new HashSet<>();
        java.util.Map<String, JSONObject> rows = new java.util.HashMap<>();
        boolean mainDatabaseVerified = false;
        for (int index = 0; index < files.length(); index++) {
            JSONObject row = files.optJSONObject(index);
            if (row == null) return false;
            String name = row.optString("name", "");
            if (!(DATABASE.equals(name) || (DATABASE + "-wal").equals(name)
                    || (DATABASE + "-shm").equals(name)) || !seen.add(name)) {
                return false;
            }
            rows.put(name, row);
            long expectedBytes = row.optLong("bytes", -1L);
            String expectedHash = row.optString("sha256", "").toLowerCase(Locale.US);
            File exact = new File(canonicalManifest.getParentFile(), name).getCanonicalFile();
            if (!exact.getParentFile().equals(canonicalManifest.getParentFile())
                    || !exact.isFile()
                    || exact.length() != expectedBytes
                    || !expectedHash.matches("[a-f0-9]{64}")
                    || !expectedHash.equals(sha256(exact))) {
                return false;
            }
            if (DATABASE.equals(name)) mainDatabaseVerified = true;
        }
        if (!mainDatabaseVerified) return false;

        Set<String> currentFiles = new HashSet<>();
        for (String suffix : new String[]{"", "-wal", "-shm"}) {
            String name = DATABASE + suffix;
            File current = new File(currentDatabase.getAbsolutePath() + suffix);
            if (!current.isFile()) continue;
            currentFiles.add(name);
            JSONObject row = rows.get(name);
            if (row == null
                    || current.length() != row.optLong("bytes", -1L)
                    || !row.optString("sha256", "").equalsIgnoreCase(sha256(current))) {
                return false;
            }
        }
        return currentFiles.equals(seen);
    }

    private static JSONObject readManifest(File manifest) throws Exception {
        try (FileInputStream input = new FileInputStream(manifest);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (output.size() + read > 64 * 1024) {
                    throw new IllegalStateException("Backup manifest exceeded limit");
                }
                output.write(buffer, 0, read);
            }
            return new JSONObject(new String(
                    output.toByteArray(), java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    private static void copyAndSync(File source, File target) throws Exception {
        try (FileInputStream input = new FileInputStream(source);
             FileOutputStream output = new FileOutputStream(target, false)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) output.write(buffer, 0, read);
            output.getFD().sync();
        }
    }

    private static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) digest.update(buffer, 0, read);
        }
        StringBuilder value = new StringBuilder(64);
        for (byte item : digest.digest()) {
            value.append(String.format(Locale.US, "%02x", item));
        }
        return value.toString();
    }

    private static Result allowed(String status, String path, String hash) {
        return new Result(true, status, path, hash);
    }

    private static Result blocked(String status, String path, String hash) {
        return new Result(false, status, path, hash);
    }
}
