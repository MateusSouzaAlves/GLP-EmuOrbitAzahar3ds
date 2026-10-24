// SPDX-License-Identifier: GPL-3.0-or-later
package com.mateussouza.emuorbit.n3ds.core;

import com.mateussouza.emuorbit.advance.data.storage.*;
import com.mateussouza.emuorbit.advance.data.save.protection.v2.*;
import java.io.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

/** Feature-owned LOCAL capture adapter. No ROM scan, JNI call, global checkpoint
 * or new directory migration. Transport/UI activation still requires the 3DS
 * transactional restore and actual native/release qualification. The base must
 * call this only in its main process and with an already active stopped sync;
 * its previous QuiescentTicket becomes stale and must NOT be reused afterwards.
 * This adapter deliberately does not upload, apply a restore or grant an ACK. */
public final class Nintendo3DsSaveBackupCapture {
    public record Report(int captured, int empty, int aliases) { }
    private record Candidate(Nintendo3DsSaveSource.Ticket source,boolean progress) { }
    private record Qualified(Nintendo3DsLoadedSaveMapping.Cohort history,
            Nintendo3DsSaveCensus census,List<Candidate> candidates,int aliases) { }
    private Nintendo3DsSaveBackupCapture() { }

    /** Reflection ABI from the base: no feature-specific return type. An
     * untouched installation has no 3DS data; do not create/migrate its layout.
     * Existing but unqualified/partial/legacy trees still fail closed in collect.
     * This method alone does not enable public coverage or a restore choice. */
    public static int[] collectForBase(File files, File scratch, File noBackup,
            SaveBackupConsentRepository permissions, SaveBackupConsent consent,
            SaveBackupStreamingCodec.Guard cancellation) throws IOException {
        if (files == null || scratch == null || noBackup == null || permissions == null
                || consent == null || cancellation == null) throw new IOException("Missing 3DS bridge inputs");
        try (var access = Nintendo3DsDataAccessGuard.beginCapture()) {
            SaveBackupStreamingCodec.Guard guard = () -> { cancellation.check();permissions.check(consent);access.checkCapture(); };
            guard.check();Path root = directory(files);
            if (!Files.exists(root.resolve("nintendo3ds"), NOFOLLOW_LINKS)) {
                // Still use the feature's actual admission for this census.
                // The base expects exactly its begin/close, not an arbitrary
                // intervening native/data owner hidden by a renewed ticket.
                directory(scratch);directory(noBackup);guard.check();return new int[]{0,0,0};
            }
            var report = collectOwned(files, scratch, noBackup, permissions, consent, cancellation, access);
            return new int[]{report.captured(), report.empty(), report.aliases()};
        }
    }

    public static Report collect(File trustedFiles, File trustedPrivateScratch, File trustedNoBackup,
            SaveBackupConsentRepository permissions, SaveBackupConsent consent,
            SaveBackupStreamingCodec.Guard cancellation) throws IOException {
        if (trustedFiles == null || trustedPrivateScratch == null || trustedNoBackup == null
                || permissions == null || consent == null || cancellation == null)
            throw new IOException("Missing stopped 3DS local capture inputs");
        try (var access = Nintendo3DsDataAccessGuard.beginCapture()) {
            return collectOwned(trustedFiles,trustedPrivateScratch,trustedNoBackup,permissions,consent,cancellation,access);
        }
    }
    private static Report collectOwned(File trustedFiles, File trustedPrivateScratch, File trustedNoBackup,
            SaveBackupConsentRepository permissions, SaveBackupConsent consent,
            SaveBackupStreamingCodec.Guard cancellation, Nintendo3DsDataAccessGuard.Lease access) throws IOException {
            SaveBackupStreamingCodec.Guard guard = () -> { cancellation.check();permissions.check(consent);access.checkCapture(); };
            guard.check();Path files = directory(trustedFiles), scratch = directory(trustedPrivateScratch);
            Path noBackup = directory(trustedNoBackup), owner = files.resolve("nintendo3ds/v1");
            Path user = owner.resolve("save/Azahar");
            if (scratch.startsWith(user) || noBackup.startsWith(user))
                throw new IOException("3DS private capture storage cannot be in its live save tree");
            // Do NOT call StorageLayout.open here: creating its auxiliary
            // directories is a data mutation, not a read-only capture census.
            // Qualify the whole local cohort BEFORE any publication. Only this
            // read-only preflight has a typed partial outcome. Freeze/copy/CAS,
            // capacity, account/consent and owner failures still abort the job.
            var qualified = qualify(owner,user,guard);
            var history=qualified.history();var census=qualified.census();
            SaveBackupArtifactStore store = new SaveBackupArtifactStore(noBackup);int captured = 0, empty = 0;
            for (Candidate candidate : qualified.candidates()) {
                guard.check();var source=candidate.source();source.check();
                if (!candidate.progress()) { empty++;continue; }
                try (var copy = Nintendo3DsSaveCapture.freeze(source, scratch, guard);
                        InputStream payload = copy.open(guard)) {
                    SaveBackupArtifact artifact = new SaveBackupArtifact("N3DS", copy.gameDataKey,
                            SaveBackupArtifact.Kind.IN_GAME_SAVE, 0, UUID.randomUUID().toString(), "",
                            System.currentTimeMillis(), 0, copy.payloadBytes, copy.payloadSha256,
                            compatibility(source.descriptor()), List.of());
                    store.record(artifact, payload, true, guard, commit -> {
                        // Full metadata + SD census CAS outside the short global
                        // publication lock; feature exclusion lasts through it.
                        source.check();guard.check();
                        history.whileUnchanged(() -> permissions.whileCurrent(consent, () -> access.publishCapture(() -> {
                            cancellation.check();commit.run();
                        })));
                    });
                    captured++;
                }
            }
            history.check();census.check();guard.check();return new Report(captured, empty, qualified.aliases());
    }
    private static Qualified qualify(Path owner,Path user,SaveBackupStreamingCodec.Guard guard) throws IOException {
        try {
            var history=Nintendo3DsLoadedSaveMapping.observeCohort(owner.resolve("save-mappings"),guard);
            var census=Nintendo3DsSaveCensus.inspect(user,history,guard);
            Map<String,String> representatives=new TreeMap<>();int aliases=0;
            for(String id:history.libraryIds()) {
                guard.check();var descriptor=history.descriptor(id,Nintendo3DsStorageLayout.CURRENT_CORE_REVISION);
                if(representatives.putIfAbsent(descriptor.programId(),id)!=null)aliases++;
            }
            List<Candidate> candidates=new ArrayList<>();
            for(String id:representatives.values()) {
                guard.check();var source=Nintendo3DsSaveSource.inspectObserved(id,
                        Nintendo3DsStorageLayout.CURRENT_CORE_REVISION,user,history,census,guard);
                var preview=Nintendo3DsSavePreview.inspect(source,guard);
                // Initialization alone is not game progress or a tombstone.
                boolean progress=preview.entries().stream().anyMatch(entry->!entry.directory()
                        && entry.bytes()>0 && preview.roots().stream().anyMatch(root->
                        root.kind()==Nintendo3DsSaveDescriptor.RootKind.DIRECTORY
                        && entry.relativePath().startsWith(root.relativePath()+"/")));
                candidates.add(new Candidate(source,progress));
            }
            history.check();census.check();guard.check();
            return new Qualified(history,census,List.copyOf(candidates),aliases);
        } catch(InterruptedIOException canceled) {throw canceled;}
        catch(IOException unqualified) {
            // A revoked grant, cancel or lost actual data admission takes
            // precedence over classification of the unreadable local tree.
            guard.check();throw new Nintendo3DsSaveBackupBridge.UnqualifiedData(unqualified);
        }
    }
    private static Path directory(File directory) throws IOException {
        Path path = directory.toPath().toAbsolutePath().normalize();
        if (!Files.isDirectory(path, NOFOLLOW_LINKS) || !path.toRealPath().equals(path))
            throw new IOException("3DS capture requires canonical private directories");
        return path;
    }
    static String compatibility(Nintendo3DsSaveDescriptor descriptor) throws IOException {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(("EmuOrbit/N3DS/SD/v1\n"
                    + Nintendo3DsStorageLayout.CURRENT_CORE_REVISION + "\n" + descriptor.programId()
                    + "\n" + descriptor.exheaderSha256()).getBytes(StandardCharsets.US_ASCII));
            StringBuilder hash = new StringBuilder(64);
            for (byte value : bytes) hash.append(String.format(Locale.ROOT, "%02x", value & 255));
            return hash.toString();
        } catch (NoSuchAlgorithmException impossible) { throw new IOException("SHA-256 unavailable", impossible); }
    }
}
