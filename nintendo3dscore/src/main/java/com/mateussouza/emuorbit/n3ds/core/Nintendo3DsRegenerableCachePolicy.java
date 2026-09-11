// SPDX-License-Identifier: GPL-3.0-or-later
package com.mateussouza.emuorbit.n3ds.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/** Measures and clears only explicitly regenerable Nintendo 3DS trees. */
public final class Nintendo3DsRegenerableCachePolicy {
    private static final int MAXIMUM_ENTRIES = 100_000;

    private final Path coreCacheRoot;
    private final Path shaderCacheRoot;
    private final Path transientRoot;

    public Nintendo3DsRegenerableCachePolicy(Nintendo3DsStorageLayout storageLayout) {
        Nintendo3DsStorageLayout checked = Objects.requireNonNull(storageLayout);
        coreCacheRoot = normalize(checked.getCacheDirectory().toPath());
        shaderCacheRoot = normalize(checked.getShaderCacheDirectory().toPath());
        transientRoot = normalize(checked.getTransientDirectory().toPath());
    }

    public synchronized Report inspect() throws IOException {
        return report(scanAll());
    }

    /** Caller must close the native session before invoking this mutating operation. */
    synchronized Report clearAfterCoreClosed() throws IOException {
        List<RootScan> scans = scanAll();
        Report removed = report(scans);
        for (RootScan scan : scans) {
            for (Path child : scan.childrenDescending) {
                Files.deleteIfExists(child);
            }
            Files.createDirectories(scan.root);
        }
        return removed;
    }

    private List<RootScan> scanAll() throws IOException {
        List<RootScan> scans = new ArrayList<>();
        scans.add(scan(coreCacheRoot));
        scans.add(scan(shaderCacheRoot));
        scans.add(scan(transientRoot));
        return scans;
    }

    private static RootScan scan(Path root) throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return new RootScan(root, List.of(), 0, 0);
        }
        if (Files.isSymbolicLink(root) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Uma raiz de cache 3DS não é um diretório privado seguro.");
        }
        List<Path> children;
        try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
            children = paths
                    .filter(path -> !path.equals(root))
                    .collect(Collectors.toList());
        }
        if (children.size() > MAXIMUM_ENTRIES) {
            throw new IOException("O cache 3DS excede o limite seguro de entradas.");
        }
        long bytes = 0;
        int files = 0;
        for (Path child : children) {
            Path normalized = normalize(child);
            if (!normalized.startsWith(root) || Files.isSymbolicLink(child)) {
                throw new IOException("O cache 3DS contém um caminho externo ou link inválido.");
            }
            if (Files.isRegularFile(child, LinkOption.NOFOLLOW_LINKS)) {
                bytes = Math.addExact(bytes, Files.size(child));
                files++;
            } else if (!Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("O cache 3DS contém uma entrada não suportada.");
            }
        }
        children.sort(Comparator.reverseOrder());
        return new RootScan(root, children, files, bytes);
    }

    private static Report report(List<RootScan> scans) {
        RootScan core = scans.get(0);
        RootScan shaders = scans.get(1);
        RootScan transientFiles = scans.get(2);
        return new Report(
                core.fileCount,
                core.bytes,
                shaders.fileCount,
                shaders.bytes,
                transientFiles.fileCount,
                transientFiles.bytes);
    }

    private static Path normalize(Path path) {
        return path.toAbsolutePath().normalize();
    }

    private static final class RootScan {
        private final Path root;
        private final List<Path> childrenDescending;
        private final int fileCount;
        private final long bytes;

        private RootScan(Path root, List<Path> childrenDescending, int fileCount, long bytes) {
            this.root = root;
            this.childrenDescending = childrenDescending;
            this.fileCount = fileCount;
            this.bytes = bytes;
        }
    }

    /** Aggregate-only cache inventory; no filenames or game identity leave the policy. */
    public static final class Report {
        private final int coreCacheFiles;
        private final long coreCacheBytes;
        private final int shaderCacheFiles;
        private final long shaderCacheBytes;
        private final int transientFiles;
        private final long transientBytes;

        Report(
                int coreCacheFiles,
                long coreCacheBytes,
                int shaderCacheFiles,
                long shaderCacheBytes,
                int transientFiles,
                long transientBytes) {
            this.coreCacheFiles = Math.max(0, coreCacheFiles);
            this.coreCacheBytes = Math.max(0, coreCacheBytes);
            this.shaderCacheFiles = Math.max(0, shaderCacheFiles);
            this.shaderCacheBytes = Math.max(0, shaderCacheBytes);
            this.transientFiles = Math.max(0, transientFiles);
            this.transientBytes = Math.max(0, transientBytes);
        }

        public static Report empty() {
            return new Report(0, 0, 0, 0, 0, 0);
        }

        public int getCoreCacheFiles() { return coreCacheFiles; }
        public long getCoreCacheBytes() { return coreCacheBytes; }
        public int getShaderCacheFiles() { return shaderCacheFiles; }
        public long getShaderCacheBytes() { return shaderCacheBytes; }
        public int getTransientFiles() { return transientFiles; }
        public long getTransientBytes() { return transientBytes; }
        public int getTotalFiles() {
            return coreCacheFiles + shaderCacheFiles + transientFiles;
        }
        public long getTotalBytes() {
            return coreCacheBytes + shaderCacheBytes + transientBytes;
        }
    }
}
