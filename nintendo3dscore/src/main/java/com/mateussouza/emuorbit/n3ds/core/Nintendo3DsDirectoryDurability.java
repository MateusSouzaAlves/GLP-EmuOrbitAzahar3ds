// SPDX-License-Identifier: GPL-3.0-or-later
package com.mateussouza.emuorbit.n3ds.core;

import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import java.io.FileDescriptor;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Android-only directory fsync for the dormant per-title restore primitive.
 * This does not grant a data/native lease, authorize a restore or acknowledge
 * native acceptance. The caller must own and pin the private directory tree.
 * No fallback to a no-op when the filesystem refuses durability. */
public final class Nintendo3DsDirectoryDurability {
    // Stable Linux/Android UAPI flag: NDK asm-generic/fcntl.h O_CLOEXEC=02000000.
    // The kernel supports this on every supported ABI/API26; only the public
    // OsConstants Java field was added in API27 (fcntlInt is public from30).
    // Passing the UAPI flag through public Os.open keeps atomic close-on-exec
    // on API26 without hidden APIs, reflection or a weaker two-call fallback.
    private static final int CLOSE_ON_EXEC = 0x80000;
    private Nintendo3DsDirectoryDurability() { }
    public static void sync(Path directory) throws IOException {
        if (directory == null || !directory.isAbsolute() || !directory.normalize().equals(directory)
                || Files.isSymbolicLink(directory) || !directory.toRealPath().equals(directory))
            throw new IOException("Unsafe 3DS directory durability path");
        FileDescriptor opened = null;IOException failure = null;
        try {
            StructStat before = Os.lstat(directory.toString());
            if (!OsConstants.S_ISDIR(before.st_mode)) throw new IOException("3DS durability requires a directory");
            // O_DIRECTORY is not a public Android OsConstants API. Read-only
            // O_NOFOLLOW plus fstat rejects a file/replaced inode before fsync.
            opened = Os.open(directory.toString(), OsConstants.O_RDONLY | OsConstants.O_NOFOLLOW | CLOSE_ON_EXEC, 0);
            requireSame(before, Os.fstat(opened));Os.fsync(opened);
            requireSame(before, Os.lstat(directory.toString()));
            if (!directory.toRealPath().equals(directory)) throw new IOException("3DS directory changed during fsync");
        } catch (ErrnoException error) { failure = new IOException("Cannot persist 3DS directory entry", error);throw failure; }
        catch (IOException error) { failure = error;throw error; }
        finally {
            if (opened != null) try { Os.close(opened); }
            catch (ErrnoException error) {
                var close = new IOException("Cannot close 3DS directory durability handle", error);
                if (failure != null) failure.addSuppressed(close);else throw close;
            }
        }
    }
    private static void requireSame(StructStat before, StructStat current) throws IOException {
        if (!OsConstants.S_ISDIR(current.st_mode) || before.st_dev != current.st_dev || before.st_ino != current.st_ino)
            throw new IOException("3DS directory inode changed during durability operation");
    }
}
