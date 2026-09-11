// SPDX-License-Identifier: GPL-3.0-or-later
package com.mateussouza.emuorbit.n3ds.core;

import android.content.ContentResolver;
import android.content.Intent;
import android.net.Uri;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * Inspects and imports the user-owned Mii database consumed by the pinned Azahar core.
 *
 * <p>This class deliberately does not generate, download or bundle Mii/system data. The public
 * import entry point accepts only a user-selected SAF content URI, copies at most one MiB into a
 * same-directory staging file, flushes it, validates the records visible to Azahar and publishes
 * it atomically. Callers must close the active core session before importing.</p>
 */
public final class Nintendo3DsMiiDataManager {
    public static final String DATABASE_FILE_NAME = "CFL_DB.dat";
    public static final long MAX_DATABASE_BYTES = 1024L * 1024L;

    private static final int DATABASE_HEADER_BYTES = 0x8;
    private static final int MII_RECORD_BYTES = 0x5C;
    private static final int MII_ID_OFFSET_BYTES = 0xC;
    private static final int MAX_VISIBLE_MIIS = 100;
    private static final int REQUIRED_VISIBLE_BYTES =
            DATABASE_HEADER_BYTES + MII_RECORD_BYTES * MAX_VISIBLE_MIIS;
    private static final int COPY_BUFFER_BYTES = 32 * 1024;

    private final Nintendo3DsStorageLayout storageLayout;
    private final BooleanSupplier activeCoreSessionSupplier;

    Nintendo3DsMiiDataManager(Nintendo3DsStorageLayout storageLayout) {
        this(storageLayout, () -> false);
    }

    /**
     * Creates a manager guarded by the product host's live core-session state.
     *
     * <p>The supplier must remain true from the moment a session can start until its native close
     * has completed. Import checks it both before copying and immediately before publication.</p>
     */
    public Nintendo3DsMiiDataManager(
            Nintendo3DsStorageLayout storageLayout,
            BooleanSupplier activeCoreSessionSupplier) {
        this.storageLayout = Objects.requireNonNull(storageLayout);
        this.activeCoreSessionSupplier = Objects.requireNonNull(activeCoreSessionSupplier);
    }

    /** Creates the one-shot document picker used for an explicit user-provided database import. */
    public static Intent createSafImportIntent() {
        return new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/octet-stream");
    }

    public File getDatabaseFile() {
        return storageLayout.getMiiDatabaseFile();
    }

    public synchronized Inspection inspect() throws IOException {
        return inspectFile(getDatabaseFile());
    }

    /**
     * Imports one SAF document and closes its stream after the transactional copy.
     *
     * @throws IOException when the URI is not a SAF content URI, cannot be read, exceeds the
     *                     bound or contains no Mii record visible to Azahar
     */
    public synchronized ImportReport importFromSafAfterCoreClosed(
            ContentResolver resolver,
            Uri source)
            throws IOException {
        ContentResolver checkedResolver = Objects.requireNonNull(resolver);
        Uri checkedSource = Objects.requireNonNull(source);
        if (!ContentResolver.SCHEME_CONTENT.equals(checkedSource.getScheme())) {
            throw new IOException("A importação Mii requer um documento escolhido via SAF.");
        }
        InputStream opened;
        try {
            opened = checkedResolver.openInputStream(checkedSource);
        } catch (SecurityException exception) {
            throw new IOException("O acesso SAF ao arquivo Mii não foi autorizado.", exception);
        }
        if (opened == null) {
            throw new IOException("O provedor SAF não abriu o arquivo Mii selecionado.");
        }
        try (InputStream input = opened) {
            return importFromStream(input);
        }
    }

    synchronized ImportReport importFromStreamForTesting(InputStream source) throws IOException {
        return importFromStream(Objects.requireNonNull(source));
    }

    private ImportReport importFromStream(InputStream source) throws IOException {
        requireCoreSessionClosed();
        File destination = requireSafeDestination();
        File parent = Objects.requireNonNull(destination.getParentFile());
        Path stagedPath = Files.createTempFile(parent.toPath(), ".CFL_DB.", ".pending");
        File staged = stagedPath.toFile();
        MessageDigest digest = sha256Digest();
        long copied = 0;
        boolean published = false;
        try {
            byte[] buffer = new byte[COPY_BUFFER_BYTES];
            try (FileOutputStream fileOutput = new FileOutputStream(staged);
                 OutputStream output = new BufferedOutputStream(fileOutput)) {
                int count;
                int readsWithoutProgress = 0;
                while ((count = source.read(buffer)) != -1) {
                    if (count == 0) {
                        readsWithoutProgress++;
                        if (readsWithoutProgress > 16) {
                            throw new IOException(
                                    "O provedor SAF parou de fornecer dados do arquivo Mii.");
                        }
                        continue;
                    }
                    readsWithoutProgress = 0;
                    copied = Math.addExact(copied, count);
                    if (copied > MAX_DATABASE_BYTES) {
                        throw new IOException("O arquivo Mii excede o limite seguro de 1 MiB.");
                    }
                    output.write(buffer, 0, count);
                    digest.update(buffer, 0, count);
                }
                output.flush();
                fileOutput.getChannel().force(true);
            } catch (ArithmeticException exception) {
                throw new IOException("O tamanho do arquivo Mii é inválido.", exception);
            }

            Inspection stagedInspection = inspectFile(staged);
            if (stagedInspection.getStatus() == Status.MALFORMED) {
                throw new IOException(
                        "O arquivo Mii está incompleto ou fora dos limites aceitos.");
            }
            if (stagedInspection.getStatus() != Status.READY) {
                throw new IOException("O arquivo selecionado não contém um Mii visível ao Azahar.");
            }

            requireCoreSessionClosed();
            moveAtomically(staged, destination);
            published = true;
            return new ImportReport(
                    copied,
                    hex(digest.digest()),
                    stagedInspection.getVisibleMiiCount());
        } finally {
            if (!published) {
                Files.deleteIfExists(stagedPath);
            }
        }
    }

    private void requireCoreSessionClosed() throws IOException {
        if (activeCoreSessionSupplier.getAsBoolean()) {
            throw new IOException("Feche a sessão Nintendo 3DS antes de importar dados Mii.");
        }
    }

    private File requireSafeDestination() throws IOException {
        File root = storageLayout.getUserDirectory().getCanonicalFile();
        File destination = getDatabaseFile();
        Path rootPath = root.toPath();
        Path destinationPath = destination.toPath().toAbsolutePath().normalize();
        if (!destinationPath.startsWith(rootPath)) {
            throw new IOException("O destino Mii saiu do armazenamento privado 3DS.");
        }

        Path parent = Objects.requireNonNull(destinationPath.getParent());
        Path relativeParent = rootPath.relativize(parent);
        Path current = rootPath;
        for (Path component : relativeParent) {
            current = current.resolve(component);
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isSymbolicLink(current)
                        || !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("O destino privado do arquivo Mii é inválido.");
                }
            } else {
                Files.createDirectory(current);
            }
        }
        if (Files.isSymbolicLink(destinationPath)
                || (Files.exists(destinationPath, LinkOption.NOFOLLOW_LINKS)
                && !Files.isRegularFile(destinationPath, LinkOption.NOFOLLOW_LINKS))) {
            throw new IOException("O arquivo Mii existente não é um arquivo privado regular.");
        }
        if (!parent.toRealPath(LinkOption.NOFOLLOW_LINKS).startsWith(rootPath)) {
            throw new IOException("O destino Mii resolveu fora do armazenamento privado 3DS.");
        }
        return destinationPath.toFile();
    }

    private static Inspection inspectFile(File database) throws IOException {
        Path path = database.toPath();
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return new Inspection(Status.MISSING, 0, 0);
        }
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            return new Inspection(Status.MALFORMED, 0, 0);
        }
        long fileBytes = Files.size(path);
        if (fileBytes < REQUIRED_VISIBLE_BYTES || fileBytes > MAX_DATABASE_BYTES) {
            return new Inspection(Status.MALFORMED, fileBytes, 0);
        }

        byte[] visible = new byte[REQUIRED_VISIBLE_BYTES];
        try (InputStream input = new BufferedInputStream(new FileInputStream(database))) {
            int offset = 0;
            int readsWithoutProgress = 0;
            while (offset < visible.length) {
                int count = input.read(visible, offset, visible.length - offset);
                if (count < 0) {
                    return new Inspection(Status.MALFORMED, fileBytes, 0);
                }
                if (count == 0) {
                    readsWithoutProgress++;
                    if (readsWithoutProgress > 16) {
                        throw new IOException("A leitura do arquivo Mii não avançou.");
                    }
                    continue;
                }
                readsWithoutProgress = 0;
                offset += count;
            }
        }

        int visibleMiis = 0;
        for (int index = 0; index < MAX_VISIBLE_MIIS; index++) {
            int miiId = DATABASE_HEADER_BYTES + index * MII_RECORD_BYTES + MII_ID_OFFSET_BYTES;
            if (visible[miiId] != 0
                    || visible[miiId + 1] != 0
                    || visible[miiId + 2] != 0
                    || visible[miiId + 3] != 0) {
                visibleMiis++;
            }
        }
        return new Inspection(
                visibleMiis == 0 ? Status.EMPTY : Status.READY,
                fileBytes,
                visibleMiis);
    }

    private static void moveAtomically(File source, File destination) throws IOException {
        try {
            Files.move(
                    source.toPath(),
                    destination.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            throw new IOException(
                    "O armazenamento não oferece publicação atômica para o arquivo Mii.",
                    exception);
        }
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 não está disponível.", exception);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        }
        return result.toString();
    }

    public enum Status {
        MISSING,
        MALFORMED,
        EMPTY,
        READY
    }

    public static final class Inspection {
        private final Status status;
        private final long fileBytes;
        private final int visibleMiiCount;

        private Inspection(Status status, long fileBytes, int visibleMiiCount) {
            this.status = Objects.requireNonNull(status);
            this.fileBytes = fileBytes;
            this.visibleMiiCount = visibleMiiCount;
        }

        public Status getStatus() {
            return status;
        }

        public long getFileBytes() {
            return fileBytes;
        }

        public int getVisibleMiiCount() {
            return visibleMiiCount;
        }

        public boolean isReady() {
            return status == Status.READY;
        }
    }

    public static final class ImportReport {
        private final long copiedBytes;
        private final String sha256;
        private final int visibleMiiCount;

        private ImportReport(long copiedBytes, String sha256, int visibleMiiCount) {
            this.copiedBytes = copiedBytes;
            this.sha256 = Objects.requireNonNull(sha256);
            this.visibleMiiCount = visibleMiiCount;
        }

        public long getCopiedBytes() {
            return copiedBytes;
        }

        public String getSha256() {
            return sha256;
        }

        public int getVisibleMiiCount() {
            return visibleMiiCount;
        }
    }
}
