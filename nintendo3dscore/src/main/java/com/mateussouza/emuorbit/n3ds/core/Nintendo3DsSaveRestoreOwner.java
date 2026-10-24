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

/** Real product pre-open owner. No frame/technical resume may run between
 * preparation and accepted(). Only locally OBSERVED mappings qualify roots;
 * cloud headers never grant ownership. All old roots and wires are retained.
 * The base chooser/transport must remain disabled until their own qualification;
 * this adapter consumes ONLY an already confirmed exact installation-local intent. */
final class Nintendo3DsSaveRestoreOwner {
    private final Context context;
    private final Nintendo3DsStorageLayout layout;
    private final Nintendo3DsSaveSource.Opening opening;
    private final Thread thread = Thread.currentThread();
    private final SaveBackupArtifactStore store;
    private final SaveBackupConsentRepository permissions;
    private final SaveBackupConsent consent;
    private final DriveBackupBinding accounts;
    private final DriveBackupBinding.Binding account;
    private final RestoreLaunchJournal journal;
    private RestoreLaunchJournal.Record choice;
    private Nintendo3DsRestoreIntent.Value intent;
    private Path intentFile;
    private SaveApplicationReceipt.State receipt;
    private SaveBackupStreamingCodec.Guard access = () -> { throw new IOException("3DS restoration lost its actual owner"); };

    private Nintendo3DsSaveRestoreOwner(Context context, Nintendo3DsStorageLayout layout,
            Nintendo3DsSaveSource.Opening opening, SaveBackupArtifactStore store,
            RestoreLaunchJournal.Record choice) throws IOException {
        this.context = context;this.layout = layout;this.opening = opening;this.store = store;this.choice = choice;
        permissions = SaveBackupConsentFile.open(context);consent = permissions.read();
        accounts = new DriveBackupBinding(context);account = accounts.read();
        journal = RestoreLaunchJournalFile.open(context, "N3DS", choice.plan.selected.contentId);
        if (choice.plan.selected.kind != SaveBackupArtifact.Kind.IN_GAME_SAVE || choice.plan.selected.slot != 0
                || !choice.plan.selected.compatibilityId.equals(Nintendo3DsSaveBackupCapture.compatibility(opening.descriptor())))
            throw new IOException("3DS chosen copy is not compatible with this actual title/core");
    }

    /** null means an ordinary launch, not acceptance of any selected backup.
     * Existing context-less/isolated layout constructors retain their behavior. */
    static Nintendo3DsSaveRestoreOwner prepare(Context context, Nintendo3DsStorageLayout layout,
            String contentPath, String libraryId) throws IOException {
        if (context == null || layout == null || libraryId == null) return null;
        Context app = context.getApplicationContext();
        Path expectedUser = app.getFilesDir().toPath().toRealPath().resolve("nintendo3ds/v1/save/Azahar");
        if (!layout.getUserDirectory().toPath().toRealPath().equals(expectedUser)) return null;
        if (!SaveProtectionProcess.isMain(app)) throw new IOException("3DS recovery requires its main-process owner");
        try (var sync = SaveSyncRuntime.acquireDedicatedStoppedSaveSync(1_000);
                var data = Nintendo3DsDataAccessGuard.beginCapture()) {
            Path image = new File(contentPath).toPath().toRealPath();
            Nintendo3DsSaveSource.Opening opening;
            try { opening = Nintendo3DsSaveSource.inspectOpening(image, image.getFileName().toString(), libraryId,
                    expectedUser, data::checkCapture); }
            catch (IOException unsupported) {
                // Unsupported mappings must not regress ordinary playable ROMs.
                // But an unresolved durable transaction cannot be bypassed.
                Path auxiliary = app.getNoBackupFilesDir().toPath().toRealPath().resolve("protected-saves-v2/.n3ds-restore");
                if (Files.exists(auxiliary, NOFOLLOW_LINKS))
                    reconcile(app, layout, new SaveBackupArtifactStore(app.getNoBackupFilesDir().toPath().toRealPath()), "", data);
                return null;
            }
            var store = new SaveBackupArtifactStore(app.getNoBackupFilesDir().toPath().toRealPath());
            String key = gameKey(opening.descriptor());
            reconcile(app, layout, store, key, data);
            var choice = RestoreLaunchJournalFile.readIfPresent(app, "N3DS", key);
            if (choice == null || !choice.pending()) return null;
            var owner = new Nintendo3DsSaveRestoreOwner(app, layout, opening, store, choice);
            owner.access = data::checkCapture;owner.prepareWhileStopped(data);return owner;
        }
    }

    private void prepareWhileStopped(Nintendo3DsDataAccessGuard.Lease data) throws IOException {
        checkChoice();var source = source(layout, opening.libraryId(), this::checkChoice);
        if (!Arrays.equals(source.descriptor().mappingEvidence(), opening.descriptor().mappingEvidence())
                || !source.gameDataKey().equals(choice.plan.selected.contentId))
            throw new IOException("Actual 3DS source differs from its observed native mapping");
        var selected = retained();receipt = store.applicationReceipt(selected.metadata);
        Path root = store.auxiliaryRestoreRootIfPresent();
        var pending = root == null ? List.<Path>of() : intents(root);
        for (Path file : pending) {
            var value = Nintendo3DsRestoreIntent.read(file);
            if (!value.settled() && value.choice().plan.requestId.equals(choice.plan.requestId)) {
                if (intent != null) throw new IOException("Ambiguous preserved 3DS application");
                Nintendo3DsRestoreIntent.requireChoice(value, choice);intent = value;intentFile = file;
            }
        }
        if (intent == null) {
            if (pending.size() >= SaveBackupArtifactStore.MAX_RECORDS)
                throw new IOException("Preserved 3DS intent count capacity reached; no old copy is deleted");
            if (choice.plan.stage != RestoreLaunchPlan.Stage.CONFIRMED || receipt != null && receipt.pending())
                throw new IOException("3DS previous application must reconcile before another choice");
            var before = Nintendo3DsSavePreview.inspect(source, this::checkChoice);
            if (!before.fingerprint().equals(choice.plan.expectedLocalFingerprint))
                throw new IOException("3DS game data changed after the user's recovery preview");
            Path scratch = context.getCacheDir().toPath().toRealPath();
            try (var original = Nintendo3DsSaveCapture.freeze(source, scratch, this::checkChoice)) {
                if (hasProgress(original.snapshot)) {
                    var metadata = new SaveBackupArtifact("N3DS", source.gameDataKey(), SaveBackupArtifact.Kind.IN_GAME_SAVE,
                            0, UUID.randomUUID().toString(), "", System.currentTimeMillis(), 0,
                            original.payloadBytes, original.payloadSha256,
                            Nintendo3DsSaveBackupCapture.compatibility(source.descriptor()), List.of());
                    try (var input = original.open(this::checkChoice)) {
                        store.preserveHistoricalInternal(metadata, input, true, this::checkPermissions, this::publishChoice);
                    }
                }
                Path wire = Files.createTempFile(scratch, "n3ds-chosen-", ".scratch");
                try {
                    try (var output = new FileOutputStream(wire.toFile())) {
                        store.materialize(selected, output, this::checkChoice);output.getFD().sync();
                    }
                    try (var input = Files.newInputStream(wire, NOFOLLOW_LINKS);
                            var prepared = Nintendo3DsSaveRestoreStaging.prepare(input, selected.metadata.payloadBytes,
                                    selected.metadata.payloadSha256, source, scratch, this::checkChoice)) {
                        long bound = Math.addExact(Nintendo3DsRestoreIntent.MAX_BYTES * 3L,
                                Math.multiplyExact(4L, Math.addExact(original.payloadBytes, prepared.payloadBytes)));
                        var transaction = store.prepareAuxiliaryRestore(bound, this::checkChoice, auxiliary -> {
                            Nintendo3DsDirectoryDurability.sync(auxiliary.getParent().getParent());
                            Nintendo3DsDirectoryDurability.sync(auxiliary.getParent());
                            Path game = auxiliary.resolve(source.gameDataKey());
                            if (!Files.exists(game, NOFOLLOW_LINKS)) { Files.createDirectory(game);Nintendo3DsDirectoryDurability.sync(auxiliary); }
                            Nintendo3DsRestoreIntent.safeDirectory(game);
                            String id = UUID.randomUUID().toString();
                            intent = new Nintendo3DsRestoreIntent.Value(opening.libraryId(),
                                    "tx-" + id, choice, original.snapshot.fingerprint(), prepared.snapshot.fingerprint(), false, true);
                            intentFile = Nintendo3DsRestoreIntent.file(game, intent);
                            // Reserve exact choice/UUID/fingerprints durably BEFORE
                            // any transaction or capture directory is created.
                            publishChoice(() -> Nintendo3DsRestoreIntent.write(intentFile, null, intent));
                            var tx = Nintendo3DsSaveRestoreTransaction.prepareReserved(source, prepared, game, id,
                                    Nintendo3DsDirectoryDurability::sync, this::checkChoice);
                            var status = tx.status(this::checkChoice);
                            if (!status.beforeFingerprint().equals(intent.before()) || !status.afterFingerprint().equals(intent.after()))
                                throw new IOException("3DS reserved preparation differs from its original choice");
                            var preparing = intent;var ready = intent.readyCopy();
                            publishChoice(() -> Nintendo3DsRestoreIntent.write(intentFile, preparing, ready));intent = ready;
                            return tx;
                        });
                        apply(transaction, selected, data);
                    }
                } finally { Files.deleteIfExists(wire); }
            }
        } else {
            var originalSource = source(layout, intent.libraryId(), this::checkChoice);
            var tx = Nintendo3DsSaveRestoreTransaction.reopen(originalSource,
                    intentFile.getParent().resolve(intent.transaction()), Nintendo3DsDirectoryDurability::sync, this::checkChoice);
            apply(tx, selected, data);
        }
        opening.check();checkChoice();
    }
    private void apply(Nintendo3DsSaveRestoreTransaction tx, SaveBackupArtifactStore.Entry selected,
            Nintendo3DsDataAccessGuard.Lease data) throws IOException {
        if (intent.preparing() || intent.settled()) throw new IOException("3DS preparation is not an applicable ready intent");
        Nintendo3DsRestoreIntent.requireChoice(intent, choice);
        if (receipt != null && receipt.applied != null && receipt.applied.requestId.equals(choice.plan.requestId)
                && receipt.pending == null && !receipt.cancelled) {
            requireReceipt(receipt.applied, intent);return; // Never regress an already accepted native application.
        }
        receipt = store.prepareApplication(selected, choice.plan.requestId, intent.choice().revision,
                intent.before(), intent.after(), receipt, this::checkPermissions, this::publishChoice);
        publishChoice(() -> CommonSaveResumeFenceFile.openNintendo3Ds(context, selected.metadata.contentId).arm(choice.plan.requestId));
        var applied = tx.apply(this::checkChoice);
        if (!applied.afterFingerprint().equals(intent.after()) || applied.stage() != Nintendo3DsSaveRestoreTransaction.Stage.MATERIALIZED)
            throw new IOException("3DS multi-root application was not materialized");
        if (choice.plan.stage == RestoreLaunchPlan.Stage.CONFIRMED)
            choice = journal.advance(choice, RestoreLaunchPlan.Stage.MATERIALIZED, this::publishPermissions);
        data.checkCapture();checkChoice();
    }

    /** Only called by Controller immediately AFTER real CoreSession.open and
     * before the first frame or technical state restore. Equality alone, a file
     * copy or a test callback cannot grant this native acceptance. */
    void accepted(Nintendo3DsCoreSession session) throws IOException {
        access = session::checkRestoreBoundary;checkChoice();opening.check(this::checkChoice);
        String fingerprint = Nintendo3DsSavePreview.inspect(layout.getUserDirectory().toPath().toRealPath(),
                opening.descriptor(), this::checkChoice).fingerprint();
        if (!fingerprint.equals(intent.after())) throw new IOException("3DS save changed before actual native acceptance");
        var actual = store.applicationReceipt(choice.plan.selected);
        var selection = actual == null ? null : actual.pending != null ? actual.pending : actual.applied;
        requireReceipt(selection, intent);receipt = actual;
        receipt = store.acknowledgeApplication(receipt, fingerprint, this::checkPermissions, this::publishChoice);
        choice = journal.advance(choice, RestoreLaunchPlan.Stage.NATIVE_ACKNOWLEDGED, this::publishPermissions);
        checkChoice();var previous = intent;intent = intent.settledCopy();
        publishChoice(() -> Nintendo3DsRestoreIntent.write(intentFile, previous, intent));
    }

    /** Called only after confirmed actual native unload. Current local data
     * admission compensates an old grant; revoked incoming permission is NOT
     * reused to finish a restore. Any unexpected/new live write refuses rollback. */
    void failedAfterNativeClosed() throws IOException {
        try (var sync = SaveSyncRuntime.acquireDedicatedStoppedSaveSync(1_000);
                var data = Nintendo3DsDataAccessGuard.beginCapture()) {
            compensate(context, layout, store, intentFile, data);
        }
    }
    private static void reconcile(Context context, Nintendo3DsStorageLayout layout, SaveBackupArtifactStore store,
            String launchingKey, Nintendo3DsDataAccessGuard.Lease data) throws IOException {
        Path root = store.auxiliaryRestoreRootIfPresent();if (root == null) return;
        for (Path file : intents(root)) {
            data.checkCapture();var intent = Nintendo3DsRestoreIntent.read(file);if (intent.settled()) continue;
            if (intent.preparing()) { retirePreparing(context, layout, store, file, intent, data);continue; }
            if (settleAccepted(context, store, file, intent, data)) continue;
            var current = RestoreLaunchJournalFile.readIfPresent(context, "N3DS", intent.choice().plan.selected.contentId);
            boolean same = true;
            try { Nintendo3DsRestoreIntent.requireChoice(intent, current);checkAuthorization(context, current); }
            catch (IOException revoked) { same = false; }
            if (!same) { compensate(context, layout, store, file, data);continue; }
            if (!intent.choice().plan.selected.contentId.equals(launchingKey))
                throw new IOException("Finish the pending 3DS recovery before opening another 3DS title");
        }
    }
    private static void compensate(Context context, Nintendo3DsStorageLayout layout, SaveBackupArtifactStore store,
            Path file, Nintendo3DsDataAccessGuard.Lease data) throws IOException {
        if (file == null) return;var intent = Nintendo3DsRestoreIntent.read(file);if (intent.settled()) return;
        if (intent.preparing()) { retirePreparing(context, layout, store, file, intent, data);return; }
        if (settleAccepted(context, store, file, intent, data)) return;
        var receipt = store.applicationReceipt(intent.choice().plan.selected);
        var source = source(layout, intent.libraryId(), data::checkCapture);
        var tx = Nintendo3DsSaveRestoreTransaction.reopen(source, file.getParent().resolve(intent.transaction()),
                Nintendo3DsDirectoryDurability::sync, data::checkCapture);
        var report = tx.rollback(data::checkCapture);
        if (!report.beforeFingerprint().equals(intent.before())) throw new IOException("3DS original was not recovered");
        if (receipt != null && receipt.pending != null) {
            requireReceipt(receipt.pending, intent);
            store.cancelApplication(receipt, report.beforeFingerprint(), data::checkCapture,
                    commit -> data.publishCapture(commit::run));
        }
        var journal = RestoreLaunchJournalFile.open(context, "N3DS", intent.choice().plan.selected.contentId);
        var current = journal.read();
        if (current != null && current.pending() && current.plan.requestId.equals(intent.choice().plan.requestId))
            journal.cancel(current, commit -> data.publishCapture(commit::run));
        data.publishCapture(() -> Nintendo3DsRestoreIntent.write(file, intent, intent.settledCopy()));
    }

    /** A PREPARING reservation cannot authorize apply, rollback or ACK. Retire
     * only under fresh actual stopped admission after proving live B unchanged
     * and the exact private preparation unapplied. Preserve every partial wire.
     * Never use revoked incoming permission or cancel a replacement choice. */
    private static void retirePreparing(Context context, Nintendo3DsStorageLayout layout, SaveBackupArtifactStore store,
            Path file, Nintendo3DsRestoreIntent.Value intent, Nintendo3DsDataAccessGuard.Lease data) throws IOException {
        if (!intent.preparing() || intent.settled()) throw new IOException("3DS reservation is not an unresolved preparation");
        data.checkCapture();var receipt = store.applicationReceipt(intent.choice().plan.selected);
        String request = intent.choice().plan.requestId;
        if (receipt != null && (receipt.pending != null && receipt.pending.requestId.equals(request)
                || receipt.applied != null && receipt.applied.requestId.equals(request)))
            throw new IOException("3DS preparing reservation unexpectedly has an application receipt");
        var source = source(layout, intent.libraryId(), data::checkCapture);
        Nintendo3DsSaveRestoreTransaction.verifyUnappliedPreparation(source, file.getParent().resolve(intent.transaction()),
                intent.before(), intent.after(), Nintendo3DsDirectoryDurability::sync, data::checkCapture);
        var journal = RestoreLaunchJournalFile.open(context, "N3DS", intent.choice().plan.selected.contentId);
        var current = journal.read();
        if (current != null && current.plan.requestId.equals(request)) {
            if (current.plan.stage != RestoreLaunchPlan.Stage.CONFIRMED)
                throw new IOException("3DS preparing reservation has an already advanced choice");
            if (!current.cancelled) {
                Nintendo3DsRestoreIntent.requireChoice(intent, current);
                journal.cancel(current, commit -> data.publishCapture(commit::run));
            }
        }
        data.publishCapture(() -> Nintendo3DsRestoreIntent.write(file, intent, intent.settledCopy()));
    }
    /** This receipt was published ONLY after actual native acceptance. Finishing
     * its metadata is not a new incoming restore, so a subsequently revoked
     * account/consent cannot authorize rollback of A or newer gameplay to B.
     * Never acknowledge/cancel a replacement choice, or manufacture a receipt.
     * No live tree, technical state, fence or retained document changes here. */
    private static boolean settleAccepted(Context context, SaveBackupArtifactStore store, Path file,
            Nintendo3DsRestoreIntent.Value intent, Nintendo3DsDataAccessGuard.Lease data) throws IOException {
        data.checkCapture();var receipt = store.applicationReceipt(intent.choice().plan.selected);
        if (receipt == null || receipt.applied == null || receipt.pending != null || receipt.cancelled
                || !receipt.applied.requestId.equals(intent.choice().plan.requestId)) return false;
        requireReceipt(receipt.applied, intent);
        var journal = RestoreLaunchJournalFile.open(context, "N3DS", intent.choice().plan.selected.contentId);
        var current = journal.read();
        if (current != null && !current.cancelled && current.plan.requestId.equals(intent.choice().plan.requestId)) {
            Nintendo3DsRestoreIntent.requireChoice(intent, current);
            // MATERIALIZED was durable before native acceptance. A CONFIRMED
            // regression is not proof of this ordered application: preserve it.
            if (current.plan.stage == RestoreLaunchPlan.Stage.CONFIRMED)
                throw new IOException("Accepted 3DS receipt has an unordered choice journal");
            if (current.plan.stage == RestoreLaunchPlan.Stage.MATERIALIZED)
                journal.advance(current, RestoreLaunchPlan.Stage.NATIVE_ACKNOWLEDGED,
                        commit -> data.publishCapture(commit::run));
        }
        data.publishCapture(() -> Nintendo3DsRestoreIntent.write(file, intent, intent.settledCopy()));
        return true;
    }
    private static List<Path> intents(Path root) throws IOException {
        Nintendo3DsRestoreIntent.safeDirectory(root);var result = new ArrayList<Path>();int count = 0;
        try (var games = Files.newDirectoryStream(root)) {
            for (Path game : games) {
                if (++count > SaveBackupArtifactStore.MAX_RECORDS || !game.getFileName().toString().matches("[a-f0-9]{64}"))
                    throw new IOException("Unknown/over-bound preserved 3DS game directory");
                Nintendo3DsRestoreIntent.safeDirectory(game);var names = new TreeSet<String>();var transactions = new TreeSet<String>();
                try (var files = Files.newDirectoryStream(game)) {
                    for (Path file : files) {
                        String name = file.getFileName().toString();
                        if (name.matches("tx-[a-f0-9-]{36}\\.e3i(?:\\.bak|\\.new)?")) {
                            names.add(name.substring(0, 39));
                        } else if (name.matches("tx-[a-f0-9-]{36}") && Files.isDirectory(file, NOFOLLOW_LINKS)) {
                            Nintendo3DsRestoreIntent.safeDirectory(file);transactions.add(name);
                        } else throw new IOException("Unresolved private 3DS preparation: preserve it before another native open");
                        if (names.size() + transactions.size() > SaveBackupArtifactStore.MAX_RECORDS * 2)
                            throw new IOException("3DS transaction intent capacity reached");
                    }
                }
                if (!names.containsAll(transactions)) throw new IOException("Orphan 3DS transaction requires explicit reconciliation");
                for (String name : names) {
                    Path file = game.resolve(name + ".e3i");var intent = Nintendo3DsRestoreIntent.read(file);
                    if (!transactions.contains(name) && !intent.preparing())
                        throw new IOException("Ready 3DS intent is missing its preserved transaction");
                    result.add(file);
                }
                if (result.size() > SaveBackupArtifactStore.MAX_RECORDS) throw new IOException("Preserved 3DS intent inventory over bound");
            }
        }
        return result;
    }
    private static Nintendo3DsSaveSource.Ticket source(Nintendo3DsStorageLayout layout, String libraryId,
            SaveBackupStreamingCodec.Guard guard) throws IOException {
        var history = Nintendo3DsLoadedSaveMapping.observeCohort(layout.getSaveDirectory().toPath().getParent().resolve("save-mappings"), guard);
        Path user = layout.getUserDirectory().toPath().toRealPath();
        var census = Nintendo3DsSaveCensus.inspect(user, history, guard);
        return Nintendo3DsSaveSource.inspectObserved(libraryId, Nintendo3DsStorageLayout.CURRENT_CORE_REVISION,
                user, history, census, guard);
    }
    private SaveBackupArtifactStore.Entry retained() throws IOException {
        SaveBackupArtifactStore.Entry selected = null;
        for (var entry : store.inventory(choice.plan.sourceNamespace)) if (entry.metadata.sameMetadata(choice.plan.selected)) {
            if (selected != null) throw new IOException("Ambiguous retained 3DS generation");selected = entry;
        }
        if (selected == null) throw new IOException("Selected 3DS generation is not retained");return selected;
    }
    private static void requireReceipt(SaveApplicationReceipt.Selection receipt, Nintendo3DsRestoreIntent.Value intent) throws IOException {
        var choice = intent.choice();
        if (receipt == null || !receipt.requestId.equals(choice.plan.requestId) || !receipt.choiceRevision.equals(choice.revision)
                || !receipt.artifact.sameMetadata(choice.plan.selected) || !receipt.namespace.equals(choice.plan.sourceNamespace)
                || !receipt.beforeFingerprint.equals(intent.before()) || !receipt.afterFingerprint.equals(intent.after()))
            throw new IOException("3DS application receipt does not match its preserved exact choice");
    }
    private void checkChoice() throws IOException {
        checkPermissions();var current = journal.read();
        if (current == null || current.cancelled || !current.revision.equals(choice.revision))
            throw new IOException("3DS recovery choice changed");
    }
    private void checkPermissions() throws IOException {
        if (Thread.currentThread() != thread || Thread.currentThread().isInterrupted())
            throw new InterruptedIOException("3DS recovery left its actual owner");
        access.check();permissions.check(consent);
        if (!choice.plan.sourceAccountKey.isEmpty()) {
            accounts.check(account);choice.requireAuthorization(accountKey(account), account.epoch);
        }
    }
    private void publishPermissions(SaveBackupConsentRepository.Commit commit) throws IOException {
        if (choice.plan.sourceAccountKey.isEmpty()) permissions.whileCurrent(consent, commit);
        else accounts.whileCurrent(account, () -> permissions.whileCurrent(consent, commit));
    }
    private void publishChoice(SaveBackupConsentRepository.Commit commit) throws IOException {
        journal.whileCurrent(choice, () -> publishPermissions(() -> { access.check();commit.run(); }));
    }
    private static void checkAuthorization(Context context, RestoreLaunchJournal.Record choice) throws IOException {
        if (!choice.plan.sourceAccountKey.isEmpty()) {
            var accounts = new DriveBackupBinding(context);var account = accounts.read();accounts.check(account);
            choice.requireAuthorization(accountKey(account), account.epoch);
        }
    }
    private static String accountKey(DriveBackupBinding.Binding account) throws IOException {
        byte[] identity = account.account.trim().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8);
        return SaveBackupStreamingCodec.sha256(new ByteArrayInputStream(identity), identity.length, () -> {});
    }
    static String gameKey(Nintendo3DsSaveDescriptor descriptor) throws IOException {
        byte[] identity = ("EmuOrbit/N3DS/game-data/v1\n" + descriptor.programId()).getBytes(StandardCharsets.US_ASCII);
        return SaveBackupStreamingCodec.sha256(new ByteArrayInputStream(identity), identity.length, () -> {});
    }
    private static boolean hasProgress(Nintendo3DsSavePreview.Snapshot preview) {
        return preview.entries().stream().anyMatch(entry -> !entry.directory() && entry.bytes() > 0
                && preview.roots().stream().anyMatch(root -> root.kind() == Nintendo3DsSaveDescriptor.RootKind.DIRECTORY
                    && entry.relativePath().startsWith(root.relativePath() + "/")));
    }
}
