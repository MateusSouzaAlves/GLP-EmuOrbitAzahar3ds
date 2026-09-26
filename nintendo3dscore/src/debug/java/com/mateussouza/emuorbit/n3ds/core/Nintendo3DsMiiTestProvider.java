// SPDX-License-Identifier: GPL-3.0-or-later
package com.mateussouza.emuorbit.n3ds.core;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;

import java.io.FileNotFoundException;
import java.io.IOException;

/** Debug-only in-process SAF source; it never reads user files. */
public final class Nintendo3DsMiiTestProvider extends ContentProvider {
    public static final String AUTHORITY =
            "com.mateussouza.emuorbit.n3ds.core.test.mii";
    public static final Uri DOCUMENT_URI = Uri.parse("content://" + AUTHORITY + "/CFL_DB.dat");

    private static volatile byte[] payload = new byte[0];

    public static void setPayload(byte[] value) {
        payload = value == null ? new byte[0] : value.clone();
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public String getType(Uri uri) {
        return "application/octet-stream";
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!DOCUMENT_URI.equals(uri) || !"r".equals(mode)) {
            throw new FileNotFoundException("Unknown synthetic Mii document");
        }
        byte[] snapshot = payload.clone();
        return openPipeHelper(
                uri,
                getType(uri),
                new Bundle(),
                snapshot,
                (output, ignoredUri, ignoredMime, ignoredOptions, bytes) -> {
                    try (ParcelFileDescriptor.AutoCloseOutputStream stream =
                                 new ParcelFileDescriptor.AutoCloseOutputStream(output)) {
                        stream.write(bytes);
                    } catch (IOException ignored) {
                        // The consumer may close the pipe after a deliberate validation failure.
                    }
                });
    }

    @Override
    public Cursor query(
            Uri uri,
            String[] projection,
            String selection,
            String[] selectionArgs,
            String sortOrder) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("Read-only test provider");
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("Read-only test provider");
    }

    @Override
    public int update(
            Uri uri,
            ContentValues values,
            String selection,
            String[] selectionArgs) {
        throw new UnsupportedOperationException("Read-only test provider");
    }
}
