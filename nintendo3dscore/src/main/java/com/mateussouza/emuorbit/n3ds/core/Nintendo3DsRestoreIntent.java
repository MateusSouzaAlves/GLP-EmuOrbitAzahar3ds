// SPDX-License-Identifier: GPL-3.0-or-later
package com.mateussouza.emuorbit.n3ds.core;

import com.mateussouza.emuorbit.advance.data.save.protection.v2.*;
import com.mateussouza.emuorbit.advance.data.storage.AtomicFileIo;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

/** Installation-local link between the exact user choice and its durable
 * multi-root transaction. Written/read back/fsynced BEFORE apply; never exported
 * as a save. Settled intents and original wires remain preserved, not deleted.
 * The feature's actual exclusive owner is mandatory for every file operation. */
final class Nintendo3DsRestoreIntent {
    static final int MAX_BYTES = RestoreLaunchJournalCodec.MAX_BYTES + 4096;
    record Value(String libraryId, String transaction, RestoreLaunchJournal.Record choice,
                 String before, String after, boolean settled, boolean preparing) {
        Value(String libraryId, String transaction, RestoreLaunchJournal.Record choice,
                String before, String after, boolean settled) {
            this(libraryId, transaction, choice, before, after, settled, false);
        }
        Value {
            Objects.requireNonNull(libraryId);Objects.requireNonNull(transaction);Objects.requireNonNull(choice);
            Objects.requireNonNull(before);Objects.requireNonNull(after);
        }
        Value settledCopy() { return new Value(libraryId, transaction, choice, before, after, true, preparing); }
        Value preparingCopy() { return new Value(libraryId, transaction, choice, before, after, false, true); }
        Value readyCopy() { return new Value(libraryId, transaction, choice, before, after, false, false); }
    }
    private Nintendo3DsRestoreIntent() { }
    static byte[] encode(Value value) throws IOException {
        validate(value);var bytes = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(bytes)) {
            // READY retains the exact v1 encoding, including legacy CAS bytes.
            // v2 exclusively reserves a preparation BEFORE its directory exists;
            // older readers must fail closed rather than mistake it for READY.
            out.writeInt(0x45334958);out.writeInt(value.preparing ? 2 : 1);
            out.writeUTF(value.libraryId);out.writeUTF(value.transaction);
            out.writeUTF(value.before);out.writeUTF(value.after);out.writeBoolean(value.settled);
            byte[] choice = RestoreLaunchJournalCodec.encode(value.choice);out.writeInt(choice.length);out.write(choice);
        }
        byte[] body = bytes.toByteArray();bytes.write(hash(body));
        if (bytes.size() > MAX_BYTES) throw new IOException("3DS restore intent exceeds its bound");
        return bytes.toByteArray();
    }
    static Value decode(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length < 40 || bytes.length > MAX_BYTES)
            throw new IOException("Invalid 3DS restore intent size");
        int size = bytes.length - 32;
        if (!MessageDigest.isEqual(hash(Arrays.copyOf(bytes, size)), Arrays.copyOfRange(bytes, size, bytes.length)))
            throw new IOException("3DS restore intent checksum failed");
        try (var in = new DataInputStream(new ByteArrayInputStream(bytes, 0, size))) {
            if (in.readInt() != 0x45334958)
                throw new IOException("Unknown 3DS restore intent: preserve it");
            int version = in.readInt();
            if (version != 1 && version != 2) throw new IOException("Unknown 3DS restore intent: preserve it");
            String library = in.readUTF(), transaction = in.readUTF(), before = in.readUTF(), after = in.readUTF();
            int flag = in.readUnsignedByte();if (flag > 1) throw new IOException("Invalid 3DS settlement flag");
            int length = in.readInt();
            if (length < 40 || length > RestoreLaunchJournalCodec.MAX_BYTES || length > in.available())
                throw new IOException("Invalid embedded 3DS restore choice size");
            byte[] choice = new byte[length];in.readFully(choice);
            if (in.read() != -1) throw new IOException("Trailing 3DS restore intent data");
            Value value = new Value(library, transaction, RestoreLaunchJournalCodec.decode(choice), before, after, flag == 1, version == 2);
            validate(value);return value;
        }
    }
    static void requireChoice(Value intent, RestoreLaunchJournal.Record current) throws IOException {
        var original = intent.choice;
        if (current == null || current.cancelled || !current.plan.selected.sameMetadata(original.plan.selected)
                || !current.plan.requestId.equals(original.plan.requestId)
                || !current.plan.sourceNamespace.equals(original.plan.sourceNamespace)
                || !current.plan.sourceAccountKey.equals(original.plan.sourceAccountKey)
                || !current.authorizationEpoch.equals(original.authorizationEpoch)
                || !current.plan.expectedLocalFingerprint.equals(intent.before)
                || current.plan.quickStateAuthorized != original.plan.quickStateAuthorized
                || current.plan.stage == RestoreLaunchPlan.Stage.CONFIRMED && !current.revision.equals(original.revision))
            throw new IOException("3DS restore intent no longer matches the exact user choice");
    }
    static Path file(Path gameRoot, Value value) throws IOException {
        validate(value);safeDirectory(gameRoot);
        if (!gameRoot.getFileName().toString().equals(value.choice.plan.selected.contentId))
            throw new IOException("3DS intent directory does not match its game");
        return gameRoot.resolve(value.transaction + ".e3i");
    }
    static Value read(Path file) throws IOException {
        safe(file);byte[] bytes = AtomicFileIo.readIfPresent(file.toFile(), MAX_BYTES);
        if (bytes == null) throw new IOException("Missing preserved 3DS restore intent");
        Value value = decode(bytes);
        if (!file.equals(file(file.getParent(), value))) throw new IOException("3DS intent filename mismatch");
        return value;
    }
    static void write(Path file, Value expected, Value next) throws IOException {
        safe(file);byte[] previous = AtomicFileIo.readIfPresent(file.toFile(), MAX_BYTES);
        if (!Arrays.equals(previous, expected == null ? null : encode(expected)))
            throw new IOException("3DS restore intent changed before publication");
        if (!file.equals(file(file.getParent(), next))) throw new IOException("3DS intent destination mismatch");
        byte[] bytes = encode(next);AtomicFileIo.write(file.toFile(), bytes);
        Nintendo3DsDirectoryDurability.sync(file.getParent());
        safe(file);
        if (!Arrays.equals(bytes, AtomicFileIo.readIfPresent(file.toFile(), MAX_BYTES)))
            throw new IOException("3DS restore intent durable read-back failed");
    }
    static void safeDirectory(Path path) throws IOException {
        if (!Files.isDirectory(path, NOFOLLOW_LINKS) || !path.toRealPath().equals(path))
            throw new IOException("Unsafe private 3DS restore directory");
    }
    private static void safe(Path file) throws IOException {
        safeDirectory(file.getParent());
        if (!Files.exists(file, NOFOLLOW_LINKS) && !Files.exists(file.resolveSibling(file.getFileName() + ".bak"), NOFOLLOW_LINKS)
                && Files.exists(file.resolveSibling(file.getFileName() + ".new"), NOFOLLOW_LINKS))
            throw new IOException("Uncommitted 3DS intent must be preserved for reconciliation");
        for (String suffix : List.of("", ".bak", ".new")) {
            Path path = file.resolveSibling(file.getFileName() + suffix);
            if (Files.exists(path, NOFOLLOW_LINKS)) {
                if (!Files.isRegularFile(path, NOFOLLOW_LINKS) || !path.toRealPath().equals(path))
                    throw new IOException("Unsafe preserved 3DS restore intent file");
                try { if (((Number)Files.getAttribute(path, "unix:nlink", NOFOLLOW_LINKS)).longValue() != 1)
                    throw new IOException("Linked 3DS restore intent refused"); }
                catch (UnsupportedOperationException windowsHost) { /* Host contracts, not Unix certification. */ }
            }
        }
    }
    private static void validate(Value value) throws IOException {
        if (value == null || !value.libraryId.matches("(?:n3ds-v1:[a-f0-9]{64}:[a-f0-9]{64}(?::[a-f0-9]{64})?|n3ds-archive-v1:[a-f0-9]{64})")
                || !value.transaction.matches("tx-[a-f0-9-]{36}")
                || !SaveBackupArtifact.uuid(value.transaction.substring(3))
                || !SaveBackupArtifact.digest(value.before) || !SaveBackupArtifact.digest(value.after)
                || !value.choice.plan.selected.system.equals("N3DS")
                || value.choice.plan.selected.kind != SaveBackupArtifact.Kind.IN_GAME_SAVE
                || value.choice.plan.selected.slot != 0 || value.choice.cancelled
                || value.choice.plan.stage != RestoreLaunchPlan.Stage.CONFIRMED
                || !value.before.equals(value.choice.plan.expectedLocalFingerprint))
            throw new IOException("Invalid exact 3DS restore intent");
    }
    private static byte[] hash(byte[] body) {
        try { return MessageDigest.getInstance("SHA-256").digest(body); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
}
