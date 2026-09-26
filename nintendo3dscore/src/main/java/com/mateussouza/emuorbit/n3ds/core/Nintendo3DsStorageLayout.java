// SPDX-License-Identifier: GPL-3.0-or-later
package com.mateussouza.emuorbit.n3ds.core;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/*
 * Owns the versioned private directory contract used by the Azahar libretro frontend.
 *
 * Azahar appends Azahar/ to the frontend save directory and creates its NAND,
 * virtual SD, configuration, cache and state subtrees there. Directory checkpoints include
 * only durable user data. Regenerable cache, logs, dumps, shaders and unqualified save states
 * deliberately remain outside the backup manifest.
 */
public final class Nintendo3DsStorageLayout {
    public static final int STORAGE_SCHEMA_VERSION = 1;
    public static final String CURRENT_CORE_REVISION =
            "azahar-2126.0-fbd3fb02f71e5f9ed5134037fd59bad96c7d2b8a";

    private static final String NAMESPACE = "nintendo3ds";
    private static final String VERSION_DIRECTORY = "v" + STORAGE_SCHEMA_VERSION;
    private static final String CORE_USER_DIRECTORY = "Azahar";
    private static final String MII_DATABASE_RELATIVE_PATH =
            "nand/data/00000000000000000000000000000000/"
                    + "extdata/00048000/F000000B/user/CFL_DB.dat";
    private static final String MANIFEST_MAGIC = "EMUORBIT_N3DS_DIRECTORY_CHECKPOINT";
    private static final int MANIFEST_SCHEMA_VERSION = 1;
    private static final String PORTABLE_ARCHIVE_MAGIC = "EMUORBIT_N3DS_PORTABLE_DATA";
    private static final int PORTABLE_ARCHIVE_SCHEMA_VERSION = 1;
    private static final String PORTABLE_HEADER_ENTRY = "emuorbit-n3ds.properties";
    private static final String PORTABLE_MANIFEST_ENTRY = "checkpoint.manifest";
    private static final int MAX_PORTABLE_HEADER_BYTES = 16 * 1024;
    private static final long MAX_PORTABLE_LOGICAL_BYTES = 256L * 1024 * 1024 * 1024;
    private static final int COPY_BUFFER_BYTES = 128 * 1024;
    private static final long DISK_SAFETY_BYTES = 1024 * 1024;
    private static final int MAX_MANIFEST_ENTRIES = 100_000;
    private static final long MAX_MANIFEST_BYTES = 32L * 1024 * 1024;
    private static final List<String> PERSISTENT_ROOTS = List.of(
            "nand", "sdmc", "sysdata", "config", "cheats");

    private final File privateRoot;
    private final File systemDirectory;
    private final File saveDirectory;
    private final File userDirectory;
    private final File cacheDirectory;
    private final File miiDatabaseFile;
    private final File transientDirectory;
    private final File backupDirectory;
    private final File objectDirectory;
    private final File snapshotDirectory;
    private final File stagingDirectory;
    private final File latestManifest;
    private final File pendingRestoreMarker;
    private final LongSupplier usableSpaceSupplier;
    private final RestoreFaultInjector restoreFaultInjector;

    private Nintendo3DsStorageLayout(
            File appFilesDirectory,
            File appCacheDirectory,
            LongSupplier usableSpaceSupplier,
            RestoreFaultInjector restoreFaultInjector) throws IOException {
        File files = requireDirectory(appFilesDirectory, "files").getCanonicalFile();
        File cache = requireDirectory(appCacheDirectory, "cache").getCanonicalFile();
        privateRoot = new File(new File(files, NAMESPACE), VERSION_DIRECTORY).getCanonicalFile();
        systemDirectory = child(privateRoot, "system");
        saveDirectory = child(privateRoot, "save");
        userDirectory = child(saveDirectory, CORE_USER_DIRECTORY);
        cacheDirectory = child(userDirectory, "cache");
        miiDatabaseFile = child(userDirectory, MII_DATABASE_RELATIVE_PATH);
        transientDirectory = new File(
                new File(cache, NAMESPACE), VERSION_DIRECTORY).getCanonicalFile();
        backupDirectory = child(privateRoot, "backups");
        objectDirectory = child(backupDirectory, "objects");
        snapshotDirectory = child(backupDirectory, "snapshots");
        stagingDirectory = child(backupDirectory, "staging");
        latestManifest = child(backupDirectory, "latest.manifest");
        pendingRestoreMarker = child(stagingDirectory, "restore.rollback-required");
        this.usableSpaceSupplier = Objects.requireNonNull(usableSpaceSupplier);
        this.restoreFaultInjector = Objects.requireNonNull(restoreFaultInjector);
        prepareDirectories();
    }

    /* Creates or opens the private v1 storage layout beneath Android files and cache roots. */
    public static Nintendo3DsStorageLayout open(
            File appFilesDirectory,
            File appCacheDirectory) throws IOException {
        File files = Objects.requireNonNull(appFilesDirectory).getCanonicalFile();
        File backupRoot = new File(
                new File(new File(files, NAMESPACE), VERSION_DIRECTORY), "backups");
        return new Nintendo3DsStorageLayout(
                files,
                appCacheDirectory,
                backupRoot::getUsableSpace,
                RestoreFaultInjector.NONE);
    }

    static Nintendo3DsStorageLayout openForTesting(
            File appFilesDirectory,
            File appCacheDirectory,
            LongSupplier usableSpaceSupplier) throws IOException {
        return new Nintendo3DsStorageLayout(
                appFilesDirectory,
                appCacheDirectory,
                usableSpaceSupplier,
                RestoreFaultInjector.NONE);
    }

    static Nintendo3DsStorageLayout openForTesting(
            File appFilesDirectory,
            File appCacheDirectory,
            LongSupplier usableSpaceSupplier,
            RestoreFaultInjector restoreFaultInjector) throws IOException {
        return new Nintendo3DsStorageLayout(
                appFilesDirectory,
                appCacheDirectory,
                usableSpaceSupplier,
                restoreFaultInjector);
    }

    public File getSystemDirectory() {
        return systemDirectory;
    }

    /* Directory supplied through RETRO_ENVIRONMENT_GET_SAVE_DIRECTORY. */
    public File getSaveDirectory() {
        return saveDirectory;
    }

    /* Azahar's explicit user root beneath the frontend save directory. */
    public File getUserDirectory() {
        return userDirectory;
    }

    public File getCacheDirectory() {
        return cacheDirectory;
    }

    public File getShaderCacheDirectory() {
        return new File(userDirectory, "shaders");
    }

    /* Azahar's PTM shared-extdata database read by the Mii selector frontend. */
    public File getMiiDatabaseFile() {
        return miiDatabaseFile;
    }

    public File getVirtualSdDirectory() {
        return new File(userDirectory, "sdmc");
    }

    /* App-owned scratch space, separate from durable files and safe to evict. */
    public File getTransientDirectory() {
        return transientDirectory;
    }

    public File getLatestManifest() {
        return latestManifest;
    }

    public boolean isSaveStatePersistenceEnabled() {
        return false;
    }

    /*
     * Captures one incremental checkpoint. The caller must first quiesce and close the core.
     */
    public synchronized CheckpointReport checkpointAfterCoreClosed(String coreRevision)
            throws IOException {
        String checkedRevision = requireCoreRevision(coreRevision);
        prepareDirectories();
        requireNoPendingRestoreRollback();
        clearStagingDirectory();

        ManifestSelection previousSelection = null;
        try {
            previousSelection = selectRestoreManifest(checkedRevision);
        } catch (IOException ignored) {
            // A live, quiesced tree can repair a missing or damaged backup index below.
        }
        Manifest previous = previousSelection == null ? null : previousSelection.manifest;
        long generation = Math.addExact(
                Math.max(highestSnapshotGeneration(), previous == null ? 0 : previous.generation),
                1);
        List<Entry> entries = scanPersistentEntries();
        long missingBytes = 0;
        int copiedFiles = 0;
        for (Entry entry : entries) {
            File object = objectFile(entry.sha256);
            if (!isVerifiedObject(object, entry)) {
                missingBytes = Math.addExact(missingBytes, entry.size);
                copiedFiles++;
            }
        }

        long logicalBytes = logicalBytes(entries);
        if (previous != null
                && previousSelection != null
                && !previousSelection.usedFallback
                && checkedRevision.equals(previous.coreRevision)
                && entriesEqual(previous.entries, entries)) {
            return new CheckpointReport(
                    previous.generation,
                    entries.size(),
                    logicalBytes,
                    0,
                    0,
                    entries.size(),
                    snapshotFile(previous.generation));
        }

        byte[] manifestBytes = encodeManifest(generation, checkedRevision, entries);
        if (manifestBytes.length > MAX_MANIFEST_BYTES) {
            throw new IOException("O manifesto de checkpoint 3DS excede os limites seguros.");
        }
        long requiredBytes = Math.addExact(
                missingBytes,
                Math.addExact(manifestBytes.length * 2L, DISK_SAFETY_BYTES));
        long usableBytes = Math.max(0, usableSpaceSupplier.getAsLong());
        if (usableBytes < requiredBytes) {
            throw new IOException(
                    "Espaço insuficiente para criar um checkpoint seguro dos dados 3DS.");
        }

        for (Entry entry : entries) {
            File object = objectFile(entry.sha256);
            if (!isVerifiedObject(object, entry)) {
                publishObject(entry, object);
            }
        }

        File snapshot = snapshotFile(generation);
        publishBytesAtomically(snapshot, manifestBytes);
        publishBytesAtomically(latestManifest, manifestBytes);
        clearStagingDirectory();

        return new CheckpointReport(
                generation,
                entries.size(),
                logicalBytes,
                copiedFiles,
                missingBytes,
                entries.size() - copiedFiles,
                snapshot);
    }

    /**
     * Restores the latest compatible checkpoint. The caller must first quiesce and close the core.
     */
    public synchronized RestoreReport restoreLatestAfterCoreClosed(String coreRevision)
            throws IOException {
        String checkedRevision = requireCoreRevision(coreRevision);
        prepareDirectories();
        requireNoPendingRestoreRollback();
        clearStagingDirectory();
        ManifestSelection selection = selectRestoreManifest(checkedRevision);
        Manifest manifest = selection.manifest;

        long restoreBytes = logicalBytes(manifest.entries);
        long usableBytes = Math.max(0, usableSpaceSupplier.getAsLong());
        if (usableBytes < Math.addExact(restoreBytes, DISK_SAFETY_BYTES)) {
            throw new IOException(
                    "Espaço insuficiente para restaurar o checkpoint 3DS com rollback.");
        }
        File preparedRoot = child(stagingDirectory, "restore.prepared");
        File rollbackRoot = child(stagingDirectory, "restore.rollback");
        File discardedRoot = child(stagingDirectory, "restore.discarded");
        ensureDirectory(preparedRoot);
        ensureDirectory(rollbackRoot);
        ensureDirectory(discardedRoot);
        for (String rootName : PERSISTENT_ROOTS) {
            ensureDirectory(child(preparedRoot, rootName));
        }
        for (Entry entry : manifest.entries) {
            copyObjectToPreparedTree(entry, preparedRoot);
        }
        publishBytesAtomically(
                pendingRestoreMarker,
                ("generation=" + manifest.generation + "\n").getBytes(StandardCharsets.UTF_8));

        List<RestoreSwap> swaps = new ArrayList<>();
        IOException failure = null;
        for (int index = 0; index < PERSISTENT_ROOTS.size(); index++) {
            String rootName = PERSISTENT_ROOTS.get(index);
            RestoreSwap swap = new RestoreSwap(
                    child(userDirectory, rootName),
                    child(preparedRoot, rootName),
                    child(rollbackRoot, rootName),
                    child(discardedRoot, rootName));
            swaps.add(swap);
            try {
                if (swap.destination.exists()) {
                    moveAtomically(swap.destination, swap.rollback, false);
                    swap.originalMoved = true;
                }
                restoreFaultInjector.beforePreparedRootPublished(rootName, index);
                moveAtomically(swap.prepared, swap.destination, false);
                swap.preparedMoved = true;
            } catch (IOException exception) {
                failure = exception;
                break;
            }
        }
        if (failure == null && selection.usedFallback) {
            try {
                publishBytesAtomically(
                        latestManifest,
                        Files.readAllBytes(selection.sourceFile.toPath()));
            } catch (IOException exception) {
                failure = exception;
            }
        }
        if (failure != null) {
            IOException rollbackFailure = rollbackRestore(swaps);
            if (rollbackFailure != null) {
                failure.addSuppressed(rollbackFailure);
            } else {
                Files.deleteIfExists(pendingRestoreMarker.toPath());
                clearStagingDirectory();
            }
            throw new IOException("A restauração 3DS falhou; o rollback foi solicitado.", failure);
        }

        Files.deleteIfExists(pendingRestoreMarker.toPath());
        clearStagingDirectory();
        return new RestoreReport(
                manifest.generation,
                manifest.entries.size(),
                restoreBytes,
                selection.usedFallback);
    }

    /**
     * Captures the current durable tree and writes a self-contained portable ZIP.
     * The caller must first quiesce and close the core. The destination is closed on return.
     */
    public synchronized PortableExportReport exportPortableDataAfterCoreClosed(
            String coreRevision,
            OutputStream destination) throws IOException {
        String checkedRevision = requireCoreRevision(coreRevision);
        Objects.requireNonNull(destination, "destination");
        CheckpointReport checkpoint = checkpointAfterCoreClosed(checkedRevision);
        ManifestSelection selection = selectRestoreManifest(checkedRevision);
        Manifest manifest = selection.manifest;
        byte[] manifestBytes = Files.readAllBytes(selection.sourceFile.toPath());
        long logicalBytes = logicalBytes(manifest.entries);
        LinkedHashMap<String, Entry> uniqueObjects = uniqueObjects(manifest.entries);
        byte[] header = encodePortableHeader(
                checkedRevision,
                manifest.entries.size(),
                logicalBytes,
                sha256(manifestBytes));

        try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(destination))) {
            writeZipEntry(zip, PORTABLE_HEADER_ENTRY, header);
            writeZipEntry(zip, PORTABLE_MANIFEST_ENTRY, manifestBytes);
            byte[] buffer = new byte[COPY_BUFFER_BYTES];
            for (Map.Entry<String, Entry> objectEntry : uniqueObjects.entrySet()) {
                Entry expected = objectEntry.getValue();
                File object = objectFile(objectEntry.getKey());
                if (!isVerifiedObject(object, expected)) {
                    throw new IOException("Um objeto exigido pelo arquivo portátil 3DS está ausente.");
                }
                ZipEntry zipEntry = portableZipEntry("objects/" + objectEntry.getKey());
                zip.putNextEntry(zipEntry);
                try (InputStream input = new BufferedInputStream(new FileInputStream(object))) {
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        zip.write(buffer, 0, count);
                    }
                }
                zip.closeEntry();
            }
            zip.finish();
        }
        return new PortableExportReport(
                checkpoint.getGeneration(),
                manifest.entries.size(),
                uniqueObjects.size(),
                logicalBytes);
    }

    /**
     * Validates, imports and transactionally restores a self-contained portable ZIP.
     * The caller must first quiesce and close the core. The source is closed on return.
     */
    public synchronized PortableImportReport importPortableDataAfterCoreClosed(
            String coreRevision,
            InputStream source) throws IOException {
        String checkedRevision = requireCoreRevision(coreRevision);
        Objects.requireNonNull(source, "source");
        prepareDirectories();
        requireNoPendingRestoreRollback();
        clearStagingDirectory();

        File importedManifestFile = child(stagingDirectory, "portable-import.manifest");
        Manifest importedManifest;
        LinkedHashMap<String, Entry> expectedObjects;
        long logicalBytes;
        try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(source))) {
            ZipEntry headerEntry = requireNextPortableEntry(zip, PORTABLE_HEADER_ENTRY);
            byte[] headerBytes = readBoundedZipEntry(
                    zip, MAX_PORTABLE_HEADER_BYTES, "O cabeçalho do arquivo portátil 3DS é inválido.");
            zip.closeEntry();
            PortableHeader header = parsePortableHeader(headerBytes);
            if (!checkedRevision.equals(header.coreRevision)) {
                throw new IOException("O arquivo portátil 3DS pertence a outra revisão do core.");
            }

            ZipEntry manifestEntry = requireNextPortableEntry(zip, PORTABLE_MANIFEST_ENTRY);
            byte[] manifestBytes = readBoundedZipEntry(
                    zip, MAX_MANIFEST_BYTES, "O manifesto do arquivo portátil 3DS é inválido.");
            zip.closeEntry();
            if (!sha256(manifestBytes).equals(header.manifestSha256)) {
                throw new IOException("O manifesto do arquivo portátil 3DS foi alterado.");
            }
            publishBytesToStaging(importedManifestFile, manifestBytes);
            importedManifest = readManifestIfPresent(importedManifestFile);
            if (importedManifest == null
                    || !checkedRevision.equals(importedManifest.coreRevision)
                    || importedManifest.entries.size() != header.fileCount) {
                throw new IOException("O arquivo portátil 3DS é incompatível.");
            }
            logicalBytes = logicalBytes(importedManifest.entries);
            if (logicalBytes != header.logicalBytes
                    || logicalBytes > MAX_PORTABLE_LOGICAL_BYTES) {
                throw new IOException("O tamanho declarado pelo arquivo portátil 3DS é inválido.");
            }
            expectedObjects = uniqueObjects(importedManifest.entries);
            long uniqueBytes = logicalBytes(new ArrayList<>(expectedObjects.values()));
            long requiredBytes = Math.addExact(
                    uniqueBytes,
                    Math.addExact(logicalBytes, DISK_SAFETY_BYTES));
            if (Math.max(0, usableSpaceSupplier.getAsLong()) < requiredBytes) {
                throw new IOException("Espaço insuficiente para importar os dados portáteis 3DS.");
            }

            File importedObjects = child(stagingDirectory, "portable-import.objects");
            ensureDirectory(importedObjects);
            for (Map.Entry<String, Entry> objectEntry : expectedObjects.entrySet()) {
                String hash = objectEntry.getKey();
                Entry expected = objectEntry.getValue();
                ZipEntry objectZipEntry = requireNextPortableEntry(zip, "objects/" + hash);
                File stagedObject = child(importedObjects, hash);
                copyAndVerifyPortableObject(zip, stagedObject, expected);
                zip.closeEntry();
            }
            if (zip.getNextEntry() != null) {
                throw new IOException("O arquivo portátil 3DS contém entradas inesperadas.");
            }
        } catch (IOException | RuntimeException exception) {
            clearStagingDirectory();
            throw exception;
        }

        File importedObjects = child(stagingDirectory, "portable-import.objects");
        for (Map.Entry<String, Entry> objectEntry : expectedObjects.entrySet()) {
            String hash = objectEntry.getKey();
            Entry expected = objectEntry.getValue();
            File target = objectFile(hash);
            File staged = child(importedObjects, hash);
            if (isVerifiedObject(target, expected)) {
                Files.deleteIfExists(staged.toPath());
            } else {
                ensureDirectory(Objects.requireNonNull(target.getParentFile()));
                moveAtomically(staged, target, false);
            }
        }

        long generation = Math.addExact(highestSnapshotGeneration(), 1);
        byte[] normalizedManifest = encodeManifest(
                generation, checkedRevision, importedManifest.entries);
        publishBytesAtomically(snapshotFile(generation), normalizedManifest);
        publishBytesAtomically(latestManifest, normalizedManifest);
        clearStagingDirectory();
        RestoreReport restored = restoreLatestAfterCoreClosed(checkedRevision);
        return new PortableImportReport(
                restored.getGeneration(),
                restored.getFileCount(),
                expectedObjects.size(),
                restored.getRestoredBytes());
    }

    Set<String> latestEntryPathsForTesting() throws IOException {
        Manifest manifest = readManifestIfPresent(latestManifest);
        if (manifest == null) {
            return Collections.emptySet();
        }
        LinkedHashSet<String> paths = new LinkedHashSet<>();
        for (Entry entry : manifest.entries) {
            paths.add(entry.relativePath);
        }
        return Collections.unmodifiableSet(paths);
    }

    File getObjectDirectoryForTesting() {
        return objectDirectory;
    }

    private ManifestSelection selectRestoreManifest(String coreRevision) throws IOException {
        IOException latestFailure = null;
        if (latestManifest.exists()) {
            Manifest latest = null;
            try {
                latest = readManifestIfPresent(latestManifest);
            } catch (IOException exception) {
                latestFailure = exception;
            }
            if (latest != null) {
                if (!coreRevision.equals(latest.coreRevision)) {
                    latestFailure = new IOException(
                            "O checkpoint 3DS mais recente pertence a outra revisão do core.");
                } else {
                    try {
                        verifyManifestObjects(latest);
                        return new ManifestSelection(latest, latestManifest, false);
                    } catch (IOException exception) {
                        latestFailure = exception;
                    }
                }
            }
        }

        for (File snapshot : snapshotManifestsNewestFirst()) {
            try {
                Manifest candidate = readManifestIfPresent(snapshot);
                if (candidate == null || !coreRevision.equals(candidate.coreRevision)) {
                    continue;
                }
                verifyManifestObjects(candidate);
                return new ManifestSelection(candidate, snapshot, true);
            } catch (IOException ignored) {
                // Continue towards the next older immutable snapshot.
            }
        }

        IOException failure = new IOException(
                "Nenhum checkpoint 3DS íntegro e compatível está disponível.");
        if (latestFailure != null) {
            failure.addSuppressed(latestFailure);
        }
        throw failure;
    }

    private void verifyManifestObjects(Manifest manifest) throws IOException {
        for (Entry entry : manifest.entries) {
            if (!isVerifiedObject(objectFile(entry.sha256), entry)) {
                throw new IOException("Um objeto exigido pelo checkpoint 3DS está ausente.");
            }
        }
    }

    private List<File> snapshotManifestsNewestFirst() throws IOException {
        File[] children = snapshotDirectory.listFiles();
        if (children == null) {
            throw new IOException("Não foi possível enumerar os snapshots privados 3DS.");
        }
        List<File> manifests = new ArrayList<>();
        for (File child : children) {
            if (Files.isSymbolicLink(child.toPath())) {
                throw new IOException("A coleção de snapshots 3DS contém um link inválido.");
            }
            if (child.isFile() && child.getName().matches("[0-9]{20}\\.manifest")) {
                manifests.add(child.getCanonicalFile());
                if (manifests.size() > MAX_MANIFEST_ENTRIES) {
                    throw new IOException("A coleção de snapshots 3DS excede o limite seguro.");
                }
            }
        }
        manifests.sort(Comparator.comparing(File::getName).reversed());
        return manifests;
    }

    private long highestSnapshotGeneration() throws IOException {
        long highest = 0;
        for (File manifest : snapshotManifestsNewestFirst()) {
            String digits = manifest.getName().substring(0, 20);
            try {
                highest = Math.max(highest, Long.parseLong(digits));
            } catch (NumberFormatException exception) {
                throw new IOException("Um snapshot 3DS contém geração inválida.", exception);
            }
        }
        return highest;
    }

    private void prepareDirectories() throws IOException {
        ensureDirectory(privateRoot);
        ensureDirectory(systemDirectory);
        ensureDirectory(saveDirectory);
        ensureDirectory(userDirectory);
        ensureDirectory(cacheDirectory);
        ensureDirectory(getVirtualSdDirectory());
        ensureDirectory(transientDirectory);
        ensureDirectory(backupDirectory);
        ensureDirectory(objectDirectory);
        ensureDirectory(snapshotDirectory);
        ensureDirectory(stagingDirectory);
    }

    private List<Entry> scanPersistentEntries() throws IOException {
        List<Entry> entries = new ArrayList<>();
        Path userRoot = userDirectory.toPath().toRealPath(LinkOption.NOFOLLOW_LINKS);
        for (String persistentRoot : PERSISTENT_ROOTS) {
            File root = child(userDirectory, persistentRoot);
            if (!root.exists()) {
                continue;
            }
            if (Files.isSymbolicLink(root.toPath()) || !root.isDirectory()) {
                throw new IOException("A árvore persistente 3DS contém um caminho inválido.");
            }
            try (java.util.stream.Stream<Path> paths = Files.walk(root.toPath())) {
                java.util.Iterator<Path> iterator = paths.iterator();
                while (iterator.hasNext()) {
                    Path path = iterator.next();
                    if (Files.isSymbolicLink(path)) {
                        throw new IOException("Links não são permitidos nos dados persistentes 3DS.");
                    }
                    if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                        continue;
                    }
                    Path real = path.toRealPath(LinkOption.NOFOLLOW_LINKS);
                    if (!real.startsWith(userRoot)) {
                        throw new IOException("Um arquivo persistente saiu da raiz privada 3DS.");
                    }
                    String relative = userRoot.relativize(real).toString().replace(File.separatorChar, '/');
                    validateRelativePath(relative);
                    long size = Files.size(real);
                    entries.add(new Entry(relative, size, sha256(real.toFile())));
                    if (entries.size() > MAX_MANIFEST_ENTRIES) {
                        throw new IOException("A árvore persistente 3DS excede o limite de arquivos.");
                    }
                }
            }
        }
        entries.sort(Comparator.comparing(entry -> entry.relativePath));
        return entries;
    }

    private void publishObject(Entry entry, File object) throws IOException {
        ensureDirectory(Objects.requireNonNull(object.getParentFile()));
        File staged = child(stagingDirectory, entry.sha256 + ".object.new");
        MessageDigest digest = sha256Digest();
        long copied = 0;
        byte[] buffer = new byte[COPY_BUFFER_BYTES];
        File source = child(userDirectory, entry.relativePath.replace('/', File.separatorChar));
        try (InputStream input = new BufferedInputStream(new FileInputStream(source));
             FileOutputStream fileOutput = new FileOutputStream(staged);
             OutputStream output = new BufferedOutputStream(fileOutput)) {
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
                digest.update(buffer, 0, count);
                copied = Math.addExact(copied, count);
            }
            output.flush();
            fileOutput.getChannel().force(true);
        }
        String copiedHash = hex(digest.digest());
        if (copied != entry.size || !copiedHash.equals(entry.sha256)) {
            Files.deleteIfExists(staged.toPath());
            throw new IOException("Um dado 3DS mudou durante o checkpoint quiescente.");
        }
        moveAtomically(staged, object, false);
    }

    private void copyObjectToPreparedTree(Entry entry, File preparedRoot) throws IOException {
        File source = objectFile(entry.sha256);
        File target = child(preparedRoot, entry.relativePath.replace('/', File.separatorChar));
        ensureDirectory(Objects.requireNonNull(target.getParentFile()));
        MessageDigest digest = sha256Digest();
        long copied = 0;
        byte[] buffer = new byte[COPY_BUFFER_BYTES];
        try (InputStream input = new BufferedInputStream(new FileInputStream(source));
             FileOutputStream fileOutput = new FileOutputStream(target);
             OutputStream output = new BufferedOutputStream(fileOutput)) {
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
                digest.update(buffer, 0, count);
                copied = Math.addExact(copied, count);
            }
            output.flush();
            fileOutput.getChannel().force(true);
        }
        if (copied != entry.size || !hex(digest.digest()).equals(entry.sha256)) {
            throw new IOException("Um objeto 3DS mudou durante a preparação da restauração.");
        }
    }

    private static IOException rollbackRestore(List<RestoreSwap> swaps) {
        IOException combined = null;
        for (int index = swaps.size() - 1; index >= 0; index--) {
            RestoreSwap swap = swaps.get(index);
            try {
                if (swap.preparedMoved && swap.destination.exists()) {
                    moveAtomically(swap.destination, swap.discarded, false);
                }
                if (swap.originalMoved && swap.rollback.exists()) {
                    moveAtomically(swap.rollback, swap.destination, false);
                }
            } catch (IOException exception) {
                if (combined == null) {
                    combined = new IOException(
                            "O rollback da restauração 3DS não terminou completamente.");
                }
                combined.addSuppressed(exception);
            }
        }
        return combined;
    }

    private static LinkedHashMap<String, Entry> uniqueObjects(List<Entry> entries)
            throws IOException {
        LinkedHashMap<String, Entry> unique = new LinkedHashMap<>();
        for (Entry entry : entries) {
            Entry previous = unique.putIfAbsent(entry.sha256, entry);
            if (previous != null && previous.size != entry.size) {
                throw new IOException("O manifesto 3DS reutiliza um hash com tamanho diferente.");
            }
        }
        return unique;
    }

    private static byte[] encodePortableHeader(
            String coreRevision,
            int fileCount,
            long logicalBytes,
            String manifestSha256) {
        String text = PORTABLE_ARCHIVE_MAGIC + "\t" + PORTABLE_ARCHIVE_SCHEMA_VERSION + "\n"
                + "storage\t" + STORAGE_SCHEMA_VERSION + "\n"
                + "core\t" + encodeText(coreRevision) + "\n"
                + "files\t" + fileCount + "\n"
                + "bytes\t" + logicalBytes + "\n"
                + "manifest\t" + manifestSha256 + "\n";
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static PortableHeader parsePortableHeader(byte[] bytes) throws IOException {
        String text = new String(bytes, StandardCharsets.UTF_8);
        String[] lines = text.split("\n", -1);
        if (lines.length != 7
                || !lines[6].isEmpty()
                || !(PORTABLE_ARCHIVE_MAGIC + "\t" + PORTABLE_ARCHIVE_SCHEMA_VERSION)
                        .equals(lines[0])
                || !Integer.toString(STORAGE_SCHEMA_VERSION).equals(field(lines[1], "storage"))) {
            throw new IOException("O cabeçalho do arquivo portátil 3DS é incompatível.");
        }
        String coreRevision = requireCoreRevision(decodeText(field(lines[2], "core")));
        int fileCount;
        try {
            fileCount = Math.toIntExact(parseNonNegativeLong(field(lines[3], "files")));
        } catch (ArithmeticException exception) {
            throw new IOException("O arquivo portátil 3DS excede o limite de arquivos.", exception);
        }
        long logicalBytes = parseNonNegativeLong(field(lines[4], "bytes"));
        String manifestSha256 = requireHash(field(lines[5], "manifest"));
        if (fileCount > MAX_MANIFEST_ENTRIES || logicalBytes > MAX_PORTABLE_LOGICAL_BYTES) {
            throw new IOException("O arquivo portátil 3DS excede os limites seguros.");
        }
        return new PortableHeader(coreRevision, fileCount, logicalBytes, manifestSha256);
    }

    private static ZipEntry portableZipEntry(String name) {
        ZipEntry entry = new ZipEntry(name);
        entry.setTime(0L);
        return entry;
    }

    private static void writeZipEntry(ZipOutputStream zip, String name, byte[] contents)
            throws IOException {
        zip.putNextEntry(portableZipEntry(name));
        zip.write(contents);
        zip.closeEntry();
    }

    private static ZipEntry requireNextPortableEntry(ZipInputStream zip, String expectedName)
            throws IOException {
        ZipEntry entry = zip.getNextEntry();
        if (entry == null || entry.isDirectory() || !expectedName.equals(entry.getName())) {
            throw new IOException("A estrutura do arquivo portátil 3DS é inválida.");
        }
        return entry;
    }

    private static byte[] readBoundedZipEntry(
            ZipInputStream zip,
            long maximumBytes,
            String errorMessage) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[Math.min(COPY_BUFFER_BYTES, MAX_PORTABLE_HEADER_BYTES)];
        long copied = 0;
        int count;
        while ((count = zip.read(buffer)) != -1) {
            copied = Math.addExact(copied, count);
            if (copied > maximumBytes) {
                throw new IOException(errorMessage);
            }
            output.write(buffer, 0, count);
        }
        if (copied == 0) {
            throw new IOException(errorMessage);
        }
        return output.toByteArray();
    }

    private static void publishBytesToStaging(File target, byte[] contents) throws IOException {
        ensureDirectory(Objects.requireNonNull(target.getParentFile()));
        try (FileOutputStream output = new FileOutputStream(target)) {
            output.write(contents);
            output.flush();
            output.getChannel().force(true);
        }
    }

    private static void copyAndVerifyPortableObject(
            ZipInputStream zip,
            File target,
            Entry expected) throws IOException {
        MessageDigest digest = sha256Digest();
        long copied = 0;
        byte[] buffer = new byte[COPY_BUFFER_BYTES];
        try (FileOutputStream fileOutput = new FileOutputStream(target);
             OutputStream output = new BufferedOutputStream(fileOutput)) {
            int count;
            while ((count = zip.read(buffer)) != -1) {
                copied = Math.addExact(copied, count);
                if (copied > expected.size) {
                    throw new IOException("Um objeto do arquivo portátil 3DS excede o tamanho esperado.");
                }
                output.write(buffer, 0, count);
                digest.update(buffer, 0, count);
            }
            output.flush();
            fileOutput.getChannel().force(true);
        } catch (IOException | RuntimeException exception) {
            Files.deleteIfExists(target.toPath());
            throw exception;
        }
        if (copied != expected.size || !hex(digest.digest()).equals(expected.sha256)) {
            Files.deleteIfExists(target.toPath());
            throw new IOException("Um objeto do arquivo portátil 3DS está corrompido.");
        }
    }

    private void publishBytesAtomically(File target, byte[] contents) throws IOException {
        ensureDirectory(Objects.requireNonNull(target.getParentFile()));
        File staged = child(stagingDirectory, target.getName() + ".new");
        try (FileOutputStream output = new FileOutputStream(staged)) {
            output.write(contents);
            output.flush();
            output.getChannel().force(true);
        }
        moveAtomically(staged, target, true);
    }

    private static void moveAtomically(File source, File target, boolean replace)
            throws IOException {
        try {
            if (replace) {
                Files.move(
                        source.toPath(),
                        target.toPath(),
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } else {
                Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE);
            }
        } catch (AtomicMoveNotSupportedException exception) {
            throw new IOException(
                    "O armazenamento não oferece publicação atômica para o checkpoint 3DS.",
                    exception);
        }
    }

    private byte[] encodeManifest(long generation, String coreRevision, List<Entry> entries) {
        StringBuilder text = new StringBuilder();
        text.append(MANIFEST_MAGIC).append('\t').append(MANIFEST_SCHEMA_VERSION).append('\n');
        text.append("storage\t").append(STORAGE_SCHEMA_VERSION).append('\n');
        text.append("generation\t").append(generation).append('\n');
        text.append("core\t").append(encodeText(coreRevision)).append('\n');
        text.append("files\t").append(entries.size()).append('\n');
        for (Entry entry : entries) {
            text.append("entry\t")
                    .append(encodeText(entry.relativePath)).append('\t')
                    .append(entry.size).append('\t')
                    .append(entry.sha256).append('\n');
        }
        return text.toString().getBytes(StandardCharsets.UTF_8);
    }

    private Manifest readManifestIfPresent(File manifestFile) throws IOException {
        if (!manifestFile.exists()) {
            return null;
        }
        if (!manifestFile.isFile()
                || Files.isSymbolicLink(manifestFile.toPath())
                || manifestFile.length() <= 0
                || manifestFile.length() > MAX_MANIFEST_BYTES) {
            throw new IOException("O manifesto de checkpoint 3DS excede os limites seguros.");
        }
        List<String> lines = Files.readAllLines(manifestFile.toPath(), StandardCharsets.UTF_8);
        if (lines.size() < 5
                || !(MANIFEST_MAGIC + "\t" + MANIFEST_SCHEMA_VERSION).equals(lines.get(0))) {
            throw new IOException("O manifesto de checkpoint 3DS é inválido.");
        }
        long generation = parsePositiveLong(field(lines.get(2), "generation"));
        int count = Math.toIntExact(parseNonNegativeLong(field(lines.get(4), "files")));
        if (!Integer.toString(STORAGE_SCHEMA_VERSION).equals(field(lines.get(1), "storage"))
                || count > MAX_MANIFEST_ENTRIES
                || lines.size() != count + 5) {
            throw new IOException("O manifesto de checkpoint 3DS é incompatível.");
        }
        String coreRevision = requireCoreRevision(decodeText(field(lines.get(3), "core")));
        List<Entry> entries = new ArrayList<>();
        String previousPath = null;
        for (int index = 0; index < count; index++) {
            String[] fields = lines.get(index + 5).split("\\t", -1);
            if (fields.length != 4 || !"entry".equals(fields[0])) {
                throw new IOException("Uma entrada do checkpoint 3DS é inválida.");
            }
            String relativePath = decodeText(fields[1]);
            validateRelativePath(relativePath);
            long size = parseNonNegativeLong(fields[2]);
            String hash = requireHash(fields[3]);
            if (previousPath != null && previousPath.compareTo(relativePath) >= 0) {
                throw new IOException("O manifesto 3DS contém entradas duplicadas ou fora de ordem.");
            }
            entries.add(new Entry(relativePath, size, hash));
            previousPath = relativePath;
        }
        return new Manifest(generation, coreRevision, entries);
    }

    private boolean isVerifiedObject(File object, Entry entry) throws IOException {
        if (!object.exists()) {
            return false;
        }
        if (!object.isFile()
                || Files.isSymbolicLink(object.toPath())
                || object.length() != entry.size
                || !sha256(object).equals(entry.sha256)) {
            throw new IOException("Um objeto do backup 3DS está corrompido.");
        }
        return true;
    }

    private File objectFile(String hash) throws IOException {
        return child(child(objectDirectory, hash.substring(0, 2)), hash);
    }

    private File snapshotFile(long generation) throws IOException {
        return child(
                snapshotDirectory,
                String.format(Locale.ROOT, "%020d.manifest", generation));
    }

    private static long logicalBytes(List<Entry> entries) {
        long total = 0;
        for (Entry entry : entries) {
            total = Math.addExact(total, entry.size);
        }
        return total;
    }

    private static boolean entriesEqual(List<Entry> first, List<Entry> second) {
        if (first.size() != second.size()) {
            return false;
        }
        for (int index = 0; index < first.size(); index++) {
            Entry left = first.get(index);
            Entry right = second.get(index);
            if (!left.relativePath.equals(right.relativePath)
                    || left.size != right.size
                    || !left.sha256.equals(right.sha256)) {
                return false;
            }
        }
        return true;
    }

    private void clearStagingDirectory() throws IOException {
        Path root = stagingDirectory.getCanonicalFile().toPath();
        if (!root.startsWith(backupDirectory.getCanonicalFile().toPath())) {
            throw new IOException("A área transacional 3DS saiu da raiz de backup.");
        }
        try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
            List<Path> children = paths
                    .filter(path -> !path.equals(root))
                    .sorted(Comparator.reverseOrder())
                    .collect(Collectors.toList());
            for (Path child : children) {
                if (Files.isSymbolicLink(child)) {
                    throw new IOException("A área transacional 3DS contém um link inválido.");
                }
                Files.deleteIfExists(child);
            }
        }
    }

    private void requireNoPendingRestoreRollback() throws IOException {
        if (pendingRestoreMarker.exists()) {
            throw new IOException(
                    "Uma restauração 3DS interrompida exige recuperação antes de outro checkpoint.");
        }
    }

    private static File requireDirectory(File directory, String label) throws IOException {
        File checked = Objects.requireNonNull(directory, label);
        ensureDirectory(checked);
        return checked;
    }

    private static void ensureDirectory(File directory) throws IOException {
        if (directory.isDirectory()) {
            if (Files.isSymbolicLink(directory.toPath())) {
                throw new IOException("Um diretório privado 3DS não pode ser um link.");
            }
            return;
        }
        if (directory.exists() || !directory.mkdirs()) {
            throw new IOException("Não foi possível criar um diretório privado 3DS.");
        }
    }

    private static File child(File parent, String name) throws IOException {
        File canonicalParent = parent.getCanonicalFile();
        File candidate = new File(canonicalParent, name).getCanonicalFile();
        if (!candidate.toPath().startsWith(canonicalParent.toPath())) {
            throw new IOException("Um caminho 3DS saiu da raiz privada.");
        }
        return candidate;
    }

    private static void validateRelativePath(String path) throws IOException {
        if (path.isEmpty() || path.startsWith("/") || path.contains("\\") || path.contains("\u0000")) {
            throw new IOException("O manifesto 3DS contém um caminho inválido.");
        }
        String root = path.contains("/") ? path.substring(0, path.indexOf('/')) : path;
        if (!PERSISTENT_ROOTS.contains(root)) {
            throw new IOException("O manifesto 3DS contém uma árvore não persistente.");
        }
        for (String component : path.split("/", -1)) {
            if (component.isEmpty() || ".".equals(component) || "..".equals(component)) {
                throw new IOException("O manifesto 3DS contém um caminho inseguro.");
            }
        }
    }

    private static String requireCoreRevision(String revision) {
        String checked = Objects.requireNonNull(revision).trim();
        if (checked.isEmpty() || checked.length() > 160 || checked.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("A revisão do core 3DS é inválida.");
        }
        return checked;
    }

    private static String field(String line, String key) throws IOException {
        String prefix = key + "\t";
        if (!line.startsWith(prefix)) {
            throw new IOException("O manifesto de checkpoint 3DS está incompleto.");
        }
        return line.substring(prefix.length());
    }

    private static long parsePositiveLong(String value) throws IOException {
        long parsed = parseNonNegativeLong(value);
        if (parsed == 0) {
            throw new IOException("O manifesto 3DS contém uma geração inválida.");
        }
        return parsed;
    }

    private static long parseNonNegativeLong(String value) throws IOException {
        try {
            long parsed = Long.parseLong(value);
            if (parsed >= 0) {
                return parsed;
            }
        } catch (NumberFormatException ignored) {
            // Converted to a stable storage error below.
        }
        throw new IOException("O manifesto 3DS contém um número inválido.");
    }

    private static String requireHash(String hash) throws IOException {
        if (hash.length() != 64 || !hash.matches("[0-9a-f]{64}")) {
            throw new IOException("O manifesto 3DS contém um hash inválido.");
        }
        return hash;
    }

    private static String encodeText(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                value.getBytes(StandardCharsets.UTF_8));
    }

    private static String decodeText(String value) throws IOException {
        try {
            return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException exception) {
            throw new IOException("O manifesto 3DS contém texto inválido.", exception);
        }
    }

    private static String sha256(File file) throws IOException {
        MessageDigest digest = sha256Digest();
        byte[] buffer = new byte[COPY_BUFFER_BYTES];
        try (InputStream input = new BufferedInputStream(new FileInputStream(file))) {
            int count;
            while ((count = input.read(buffer)) != -1) {
                digest.update(buffer, 0, count);
            }
        }
        return hex(digest.digest());
    }

    private static String sha256(byte[] bytes) {
        MessageDigest digest = sha256Digest();
        digest.update(bytes);
        return hex(digest.digest());
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by Android", exception);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(String.format("%02x", value & 0xff));
        }
        return result.toString();
    }

    private static final class Entry {
        private final String relativePath;
        private final long size;
        private final String sha256;

        private Entry(String relativePath, long size, String sha256) {
            this.relativePath = relativePath;
            this.size = size;
            this.sha256 = sha256;
        }
    }

    private static final class PortableHeader {
        private final String coreRevision;
        private final int fileCount;
        private final long logicalBytes;
        private final String manifestSha256;

        private PortableHeader(
                String coreRevision,
                int fileCount,
                long logicalBytes,
                String manifestSha256) {
            this.coreRevision = coreRevision;
            this.fileCount = fileCount;
            this.logicalBytes = logicalBytes;
            this.manifestSha256 = manifestSha256;
        }
    }

    private static final class RestoreSwap {
        private final File destination;
        private final File prepared;
        private final File rollback;
        private final File discarded;
        private boolean originalMoved;
        private boolean preparedMoved;

        private RestoreSwap(
                File destination,
                File prepared,
                File rollback,
                File discarded) {
            this.destination = destination;
            this.prepared = prepared;
            this.rollback = rollback;
            this.discarded = discarded;
        }
    }

    @FunctionalInterface
    interface RestoreFaultInjector {
        RestoreFaultInjector NONE = (rootName, index) -> { };

        void beforePreparedRootPublished(String rootName, int index) throws IOException;
    }

    private static final class Manifest {
        private final long generation;
        private final String coreRevision;
        private final List<Entry> entries;

        private Manifest(long generation, String coreRevision, List<Entry> entries) {
            this.generation = generation;
            this.coreRevision = coreRevision;
            this.entries = entries;
        }
    }

    private static final class ManifestSelection {
        private final Manifest manifest;
        private final File sourceFile;
        private final boolean usedFallback;

        private ManifestSelection(Manifest manifest, File sourceFile, boolean usedFallback) {
            this.manifest = manifest;
            this.sourceFile = sourceFile;
            this.usedFallback = usedFallback;
        }
    }

    /* Immutable evidence for one completed durable checkpoint. */
    public static final class CheckpointReport {
        private final long generation;
        private final int fileCount;
        private final long logicalBytes;
        private final int copiedFiles;
        private final long copiedBytes;
        private final int reusedFiles;
        private final File snapshotManifest;

        private CheckpointReport(
                long generation,
                int fileCount,
                long logicalBytes,
                int copiedFiles,
                long copiedBytes,
                int reusedFiles,
                File snapshotManifest) {
            this.generation = generation;
            this.fileCount = fileCount;
            this.logicalBytes = logicalBytes;
            this.copiedFiles = copiedFiles;
            this.copiedBytes = copiedBytes;
            this.reusedFiles = reusedFiles;
            this.snapshotManifest = snapshotManifest;
        }

        public long getGeneration() {
            return generation;
        }

        public int getFileCount() {
            return fileCount;
        }

        public long getLogicalBytes() {
            return logicalBytes;
        }

        public int getCopiedFiles() {
            return copiedFiles;
        }

        public long getCopiedBytes() {
            return copiedBytes;
        }

        public int getReusedFiles() {
            return reusedFiles;
        }

        public File getSnapshotManifest() {
            return snapshotManifest;
        }
    }

    /** Immutable evidence for one completed transactional restore. */
    public static final class RestoreReport {
        private final long generation;
        private final int fileCount;
        private final long restoredBytes;
        private final boolean fallbackSnapshotUsed;

        private RestoreReport(
                long generation,
                int fileCount,
                long restoredBytes,
                boolean fallbackSnapshotUsed) {
            this.generation = generation;
            this.fileCount = fileCount;
            this.restoredBytes = restoredBytes;
            this.fallbackSnapshotUsed = fallbackSnapshotUsed;
        }

        public long getGeneration() {
            return generation;
        }

        public int getFileCount() {
            return fileCount;
        }

        public long getRestoredBytes() {
            return restoredBytes;
        }

        public boolean isFallbackSnapshotUsed() {
            return fallbackSnapshotUsed;
        }
    }

    /** Immutable evidence for one complete portable archive export. */
    public static final class PortableExportReport {
        private final long generation;
        private final int fileCount;
        private final int objectCount;
        private final long logicalBytes;

        private PortableExportReport(
                long generation,
                int fileCount,
                int objectCount,
                long logicalBytes) {
            this.generation = generation;
            this.fileCount = fileCount;
            this.objectCount = objectCount;
            this.logicalBytes = logicalBytes;
        }

        public long getGeneration() {
            return generation;
        }

        public int getFileCount() {
            return fileCount;
        }

        public int getObjectCount() {
            return objectCount;
        }

        public long getLogicalBytes() {
            return logicalBytes;
        }
    }

    /** Immutable evidence for one validated portable import and transactional restore. */
    public static final class PortableImportReport {
        private final long generation;
        private final int fileCount;
        private final int objectCount;
        private final long restoredBytes;

        private PortableImportReport(
                long generation,
                int fileCount,
                int objectCount,
                long restoredBytes) {
            this.generation = generation;
            this.fileCount = fileCount;
            this.objectCount = objectCount;
            this.restoredBytes = restoredBytes;
        }

        public long getGeneration() {
            return generation;
        }

        public int getFileCount() {
            return fileCount;
        }

        public int getObjectCount() {
            return objectCount;
        }

        public long getRestoredBytes() {
            return restoredBytes;
        }
    }
}
