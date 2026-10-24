// SPDX-License-Identifier: GPL-3.0-or-later
package com.mateussouza.emuorbit.n3ds.core;

import android.content.Context;
import com.mateussouza.emuorbit.advance.data.storage.*;
import com.mateussouza.emuorbit.advance.data.save.protection.DriveBackupBinding;
import com.mateussouza.emuorbit.advance.data.save.protection.v2.*;
import com.mateussouza.emuorbit.advance.data.save.sync.SaveSyncRuntime;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

/** Stopped feature-owned UI selection. Only LOCAL OBSERVED history can map a
 * library identity; remote descriptors, filenames and dates never grant it.
 * Confirmation writes an intent only. Native owner preserves B, applies A and
 * grants its own real ACK before frames. No live saves/ROMs are changed here. */
public final class Nintendo3DsRestoreSelection {
    private Nintendo3DsRestoreSelection() { }
    public static String[][] ownersForBase(Context context,SaveBackupStreamingCodec.Guard cancellation) throws IOException {
        if(context==null || cancellation==null)throw new IOException("Missing 3DS association boundary");
        try(var sync=SaveSyncRuntime.acquireDedicatedStoppedSaveSync(0);var access=Nintendo3DsDataAccessGuard.beginCapture()) {
            SaveBackupStreamingCodec.Guard guard=()->{interrupted();cancellation.check();access.checkCapture();};
            Path files=directory(context.getFilesDir());guard.check();
            if(!Files.exists(files.resolve("nintendo3ds"),NOFOLLOW_LINKS))return new String[0][];
            var sources=sources(files,guard);List<String[]> owners=new ArrayList<>();
            for(String id:sources.history.libraryIds()) {
                var source=sources.source(id,guard);
                owners.add(new String[]{id,source.gameDataKey(),Nintendo3DsSaveBackupCapture.compatibility(source.descriptor())});
            }
            sources.history.check();sources.census.check();guard.check();return owners.toArray(new String[0][]);
        }
    }
    public static Nintendo3DsRestorePreview previewForBase(Context context,SaveBackupArtifactStore.Entry copy,
            String libraryId,DriveBackupBinding.Binding account,SaveBackupConsent contents,
            SaveBackupStreamingCodec.Guard cancellation) throws IOException {
        requireInputs(context,copy,account,contents,cancellation);
        try(var sync=SaveSyncRuntime.acquireDedicatedStoppedSaveSync(0);var access=Nintendo3DsDataAccessGuard.beginCapture()) {
            var accounts=new DriveBackupBinding(context);var permissions=SaveBackupConsentFile.open(context);
            SaveBackupStreamingCodec.Guard guard=()->{interrupted();cancellation.check();access.checkCapture();
                accounts.whileUnchanged(account,()->{});permissions.check(contents);};
            guard.check();requireNamespace(copy,account);
            var sources=sources(directory(context.getFilesDir()),guard);var source=sources.source(libraryId,guard);
            requireCompatible(copy,source);
            var store=new SaveBackupArtifactStore(directory(context.getNoBackupFilesDir()));
            var journal=RestoreLaunchJournalFile.open(context,"N3DS",copy.metadata.contentId);
            var choice=journal.read();requireNoPending(store,copy,choice);
            verifyRetained(context,store,copy,source,guard);
            var before=Nintendo3DsSavePreview.inspect(source,guard);source.check();guard.check();
            journal.whileUnchanged(choice,()->{});
            return new Nintendo3DsRestorePreview(copy,libraryId,before.fingerprint(),source.cohortSha256(),choice,account,contents);
        }
    }
    public static void chooseForBase(Context context,Nintendo3DsRestorePreview expected,
            SaveBackupStreamingCodec.Guard cancellation) throws IOException {
        if(expected==null)throw new IOException("Missing 3DS user preview");
        var copy=expected.copy;requireInputs(context,copy,expected.account,expected.contents,cancellation);
        try(var sync=SaveSyncRuntime.acquireDedicatedStoppedSaveSync(0);var access=Nintendo3DsDataAccessGuard.beginCapture()) {
            var accounts=new DriveBackupBinding(context);var permissions=SaveBackupConsentFile.open(context);
            SaveBackupStreamingCodec.Guard guard=()->{interrupted();cancellation.check();access.checkCapture();
                accounts.whileUnchanged(expected.account,()->{});permissions.check(expected.contents);};
            guard.check();requireNamespace(copy,expected.account);
            var sources=sources(directory(context.getFilesDir()),guard);var source=sources.source(expected.libraryId,guard);
            requireCompatible(copy,source);
            if(!source.cohortSha256().equals(expected.cohort))throw new IOException("3DS observed cohort changed after preview");
            var store=new SaveBackupArtifactStore(directory(context.getNoBackupFilesDir()));
            var journal=RestoreLaunchJournalFile.open(context,"N3DS",copy.metadata.contentId);
            requireNoPending(store,copy,journal.read());verifyRetained(context,store,copy,source,guard);
            if(!Nintendo3DsSavePreview.inspect(source,guard).fingerprint().equals(expected.fingerprint))
                throw new IOException("3DS save changed after preview");
            source.check();guard.check();
            String accountKey=copy.namespace.startsWith("drive-")?accountKey(expected.account):"";
            var plan=new RestoreLaunchPlan(copy.metadata,UUID.randomUUID().toString(),accountKey,expected.fingerprint,
                    RestoreLaunchPlan.Stage.CONFIRMED,false,copy.namespace);
            // Full source/census checks outside the short journal publication;
            // actual feature exclusion stays held throughout confirmation.
            sources.history.whileUnchanged(()->journal.confirm(plan,accountKey.isEmpty()?"":expected.account.epoch,
                    expected.choice,commit->accounts.whileUnchanged(expected.account,()->permissions.whileCurrent(expected.contents,
                            ()->access.publishCapture(()->{cancellation.check();commit.run();})))));
        }
    }
    private record Sources(Path user,Nintendo3DsLoadedSaveMapping.Cohort history,Nintendo3DsSaveCensus census) {
        Nintendo3DsSaveSource.Ticket source(String id,SaveBackupStreamingCodec.Guard guard) throws IOException {
            try {
                return Nintendo3DsSaveSource.inspectObserved(id,Nintendo3DsStorageLayout.CURRENT_CORE_REVISION,user,history,census,guard);
            } catch(InterruptedIOException stopped){throw stopped;}
            catch(IOException unqualified){guard.check();throw new Nintendo3DsRestoreSelectionBridge.UnqualifiedData(unqualified);}
        }
    }
    private static Sources sources(Path files,SaveBackupStreamingCodec.Guard guard) throws IOException {
        guard.check();Path owner=files.resolve("nintendo3ds/v1"),user=owner.resolve("save/Azahar");
        try {
            var history=Nintendo3DsLoadedSaveMapping.observeCohort(owner.resolve("save-mappings"),guard);
            var census=Nintendo3DsSaveCensus.inspect(user,history,guard);return new Sources(user,history,census);
        } catch(InterruptedIOException stopped){throw stopped;}
        catch(IOException unqualified){guard.check();throw new Nintendo3DsRestoreSelectionBridge.UnqualifiedData(unqualified);}
    }
    private static void requireCompatible(SaveBackupArtifactStore.Entry copy,Nintendo3DsSaveSource.Ticket source) throws IOException {
        if(!source.gameDataKey().equals(copy.metadata.contentId)
                || !Nintendo3DsSaveBackupCapture.compatibility(source.descriptor()).equals(copy.metadata.compatibilityId))
            throw new IOException("3DS copy does not match its locally observed title/core");
    }
    private static void verifyRetained(Context context,SaveBackupArtifactStore store,SaveBackupArtifactStore.Entry copy,
            Nintendo3DsSaveSource.Ticket source,SaveBackupStreamingCodec.Guard guard) throws IOException {
        int matches=0;
        for(var retained:store.inventory(copy.namespace))if(retained.metadata.sameMetadata(copy.metadata)
                && retained.documentBytes==copy.documentBytes)matches++;
        if(matches!=1)throw new IOException("Exact 3DS copy is not uniquely retained");
        Path scratch=directory(context.getCacheDir()),wire=Files.createTempFile(scratch,"n3ds-choice-",".scratch");
        try {
            try(var output=new FileOutputStream(wire.toFile())){store.materialize(copy,output,guard);output.getFD().sync();}
            try(var input=Files.newInputStream(wire,NOFOLLOW_LINKS);
                    var prepared=Nintendo3DsSaveRestoreStaging.prepare(input,copy.metadata.payloadBytes,
                            copy.metadata.payloadSha256,source,scratch,guard)) {prepared.check(guard);}
        } finally {Files.deleteIfExists(wire);}
    }
    private static void requireInputs(Context context,SaveBackupArtifactStore.Entry copy,DriveBackupBinding.Binding account,
            SaveBackupConsent contents,SaveBackupStreamingCodec.Guard guard) throws IOException {
        if(context==null || account==null || contents==null || guard==null || copy==null
                || !copy.metadata.system.equals("N3DS") || copy.metadata.kind!=SaveBackupArtifact.Kind.IN_GAME_SAVE
                || copy.metadata.slot!=0)throw new IOException("Invalid 3DS internal choice inputs");
    }
    private static void requireNoPending(SaveBackupArtifactStore store,SaveBackupArtifactStore.Entry copy,
            RestoreLaunchJournal.Record choice) throws IOException {
        var receipt=store.applicationReceipt(copy.metadata);
        if(choice!=null && choice.pending() || receipt!=null && receipt.pending())
            throw new Nintendo3DsRestoreSelectionBridge.PendingRecovery();
    }
    private static void requireNamespace(SaveBackupArtifactStore.Entry copy,DriveBackupBinding.Binding account) throws IOException {
        if(!copy.namespace.equals("local") && !copy.namespace.equals("android")
                && (!account.connected() || !copy.namespace.equals("drive-"+accountKey(account))))
            throw new IOException("3DS copy belongs to a different account");
    }
    private static String accountKey(DriveBackupBinding.Binding account) throws IOException {
        byte[] bytes=account.account.trim().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8);
        return SaveBackupStreamingCodec.sha256(new ByteArrayInputStream(bytes),bytes.length,Nintendo3DsRestoreSelection::interrupted);
    }
    private static Path directory(File file) throws IOException {
        // Every caller supplies a framework-owned Context directory, never a
        // selected file/remote path. Android's private-root alias is legitimate;
        // all save descendants remain subject to their strict no-link census.
        Path path=file.getCanonicalFile().toPath().toAbsolutePath().normalize();
        if(!Files.isDirectory(path,NOFOLLOW_LINKS) || !path.toRealPath().equals(path))throw new IOException("Unsafe 3DS choice directory");
        return path;
    }
    private static void interrupted() throws IOException {
        if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("3DS recovery chooser cancelled");
    }
}
