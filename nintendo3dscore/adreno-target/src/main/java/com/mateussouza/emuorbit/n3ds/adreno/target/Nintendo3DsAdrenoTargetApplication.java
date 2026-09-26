// SPDX-License-Identifier: GPL-3.0-or-later
package com.mateussouza.emuorbit.n3ds.adreno.target;

import android.app.Application;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Private, non-production Firebase Test Lab target for the physical Adreno gate. */
public final class Nintendo3DsAdrenoTargetApplication extends Application {
    public static final String CORE_FILE = "adreno-gate-core.so";
    public static final String CONTENT_FILE = "adreno-gate-content.3dsx";

    private static final Asset CORE = new Asset(
            "n3ds-adreno/azahar_libretro.so",
            CORE_FILE,
            23_157_736L,
            "64221f5ca8e731846523669dab3ca569f796ccee57f5e4f78b29b4e0330a734c",
            true);
    private static final Asset CONTENT = new Asset(
            "n3ds-adreno/open-homebrew.3dsx",
            CONTENT_FILE,
            713_384L,
            "00fb87d97ecb866a99902740ab67e38e05f81d74295e0c3774eb62b90b0a335b",
            false);

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            materialize(CORE);
            materialize(CONTENT);
        } catch (IOException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Pinned Adreno QA inputs could not be materialized", exception);
        }
    }

    private void materialize(Asset asset) throws IOException, NoSuchAlgorithmException {
        File destination = new File(getFilesDir(), asset.outputName);
        if (matches(destination, asset)) {
            applyPermissions(destination, asset.executable);
            return;
        }
        File temporary = new File(getFilesDir(), asset.outputName + ".tmp");
        if (temporary.exists() && !temporary.delete()) {
            throw new IOException("Stale Adreno QA input could not be removed");
        }
        try (InputStream input = getAssets().open(asset.assetPath);
             FileOutputStream output = new FileOutputStream(temporary)) {
            byte[] buffer = new byte[1024 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                output.write(buffer, 0, read);
            }
            output.getFD().sync();
        }
        if (!matches(temporary, asset)) {
            throw new IOException("Materialized Adreno QA input failed identity validation");
        }
        if (destination.exists() && !destination.delete()) {
            throw new IOException("Previous Adreno QA input could not be replaced");
        }
        if (!temporary.renameTo(destination)) {
            throw new IOException("Adreno QA input could not be committed atomically");
        }
        applyPermissions(destination, asset.executable);
    }

    private static boolean matches(File file, Asset asset)
            throws IOException, NoSuchAlgorithmException {
        if (!file.isFile() || file.length() != asset.bytes) {
            return false;
        }
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[1024 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
        }
        return asset.sha256.equals(hex(digest.digest()));
    }

    private static void applyPermissions(File file, boolean executable) throws IOException {
        if (!file.setReadable(true, true)
                || !file.setWritable(false, false)
                || (executable && !file.setExecutable(true, true))) {
            throw new IOException("Adreno QA input permissions could not be restricted");
        }
    }

    private static String hex(byte[] value) {
        StringBuilder result = new StringBuilder(value.length * 2);
        for (byte item : value) {
            result.append(String.format("%02x", item & 0xff));
        }
        return result.toString();
    }

    private static final class Asset {
        final String assetPath;
        final String outputName;
        final long bytes;
        final String sha256;
        final boolean executable;

        Asset(String assetPath, String outputName, long bytes, String sha256, boolean executable) {
            this.assetPath = assetPath;
            this.outputName = outputName;
            this.bytes = bytes;
            this.sha256 = sha256;
            this.executable = executable;
        }
    }
}
