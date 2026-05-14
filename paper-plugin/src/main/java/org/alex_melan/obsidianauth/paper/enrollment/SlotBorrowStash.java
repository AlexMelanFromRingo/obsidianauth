package org.alex_melan.obsidianauth.paper.enrollment;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.zip.CRC32;
import org.alex_melan.obsidianauth.core.async.AsyncExecutor;
import org.bukkit.inventory.ItemStack;

/**
 * Per-player crash-resistant stash of an item displaced by the QR card.
 *
 * <p>File layout (multi-byte fields big-endian):
 * <pre>
 *  0   magic           4 bytes  ASCII "T2FA"
 *  4   format_version  1 byte   0x01
 *  5   slot_index      1 byte   0..8 (hotbar slot)
 *  6   item_length     4 bytes  uint32, length L of serialised ItemStack
 * 10   item_bytes      L bytes  ItemStack.serializeAsBytes() output
 * 10+L crc32           4 bytes  CRC-32 over bytes[0..(10+L-1)]
 * </pre>
 *
 * <p>Write protocol (POSIX-safe):
 * <ol>
 *   <li>Write the full record to {@code {uuid}.stash.tmp}.</li>
 *   <li>{@code FileChannel.force(true)} to flush data + metadata.</li>
 *   <li>Atomic-rename to {@code {uuid}.stash}.</li>
 *   <li>Open the parent directory and {@code force(true)} (POSIX only — silently skipped elsewhere).</li>
 * </ol>
 *
 * <p>Every operation runs on the {@link AsyncExecutor} — no I/O on the main thread.
 */
public final class SlotBorrowStash {

    public static final byte[] MAGIC = {'T', '2', 'F', 'A'};
    public static final byte FORMAT_VERSION = 0x01;

    private final AsyncExecutor async;
    private final Path stashDir;

    public SlotBorrowStash(AsyncExecutor async, Path stashDir) {
        this.async = async;
        this.stashDir = stashDir;
    }

    /** Persist the displaced item. Future completes when fsync + rename are durable. */
    public CompletableFuture<Void> save(UUID playerUuid, int slotIndex, ItemStack item) {
        return async.submit((java.util.function.Supplier<Void>) () -> {
            saveSync(playerUuid, slotIndex, item);
            return null;
        });
    }

    /** Read back the displaced item, if a stash exists. */
    public CompletableFuture<Optional<StashEntry>> load(UUID playerUuid) {
        return async.submit(() -> loadSync(playerUuid));
    }

    /** Delete the stash file, if present. */
    public CompletableFuture<Boolean> delete(UUID playerUuid) {
        return async.submit(() -> deleteSync(playerUuid));
    }

    public record StashEntry(int slotIndex, ItemStack item) {}

    // --- sync helpers ---

    void saveSync(UUID playerUuid, int slotIndex, ItemStack item) {
        if (slotIndex < 0 || slotIndex > 8) {
            throw new IllegalArgumentException("slot index must be in 0..8, got " + slotIndex);
        }
        try {
            Files.createDirectories(stashDir);
            byte[] itemBytes = item.serializeAsBytes();
            byte[] body = ByteBuffer.allocate(10 + itemBytes.length)
                    .put(MAGIC)
                    .put(FORMAT_VERSION)
                    .put((byte) slotIndex)
                    .putInt(itemBytes.length)
                    .put(itemBytes)
                    .array();
            CRC32 crc = new CRC32();
            crc.update(body);
            int crcValue = (int) crc.getValue();
            byte[] frame = ByteBuffer.allocate(body.length + 4)
                    .put(body)
                    .putInt(crcValue)
                    .array();

            Path tmpPath = stashDir.resolve(playerUuid + ".stash.tmp");
            Path finalPath = stashDir.resolve(playerUuid + ".stash");

            Files.write(tmpPath, frame);
            try (RandomAccessFile raf = new RandomAccessFile(tmpPath.toFile(), "rw");
                 FileChannel ch = raf.getChannel()) {
                ch.force(true);
            }
            Files.move(tmpPath, finalPath,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            // Parent-dir fsync (POSIX only — Windows treats this as no-op via IOException).
            try (FileChannel ch = FileChannel.open(stashDir, java.nio.file.StandardOpenOption.READ)) {
                ch.force(true);
            } catch (IOException ignored) {
                // Non-POSIX filesystem; rename is durable enough.
            }
        } catch (IOException e) {
            throw new IllegalStateException("failed to save stash for " + playerUuid, e);
        }
    }

    Optional<StashEntry> loadSync(UUID playerUuid) {
        Path path = stashDir.resolve(playerUuid + ".stash");
        if (!Files.exists(path)) return Optional.empty();
        try {
            byte[] all = Files.readAllBytes(path);
            if (all.length < 14) {
                throw new IllegalStateException("stash file too short: " + path);
            }
            if (all[0] != MAGIC[0] || all[1] != MAGIC[1]
                    || all[2] != MAGIC[2] || all[3] != MAGIC[3]) {
                throw new IllegalStateException("bad magic in stash: " + path);
            }
            if (all[4] != FORMAT_VERSION) {
                throw new IllegalStateException("unsupported format version: " + all[4]);
            }
            int slotIndex = all[5] & 0xff;
            ByteBuffer buf = ByteBuffer.wrap(all);
            int itemLength = buf.getInt(6);
            if (itemLength < 0 || itemLength > all.length - 14) {
                throw new IllegalStateException("invalid item_length: " + itemLength);
            }
            byte[] body = new byte[10 + itemLength];
            System.arraycopy(all, 0, body, 0, body.length);
            int storedCrc = buf.getInt(10 + itemLength);
            CRC32 crc = new CRC32();
            crc.update(body);
            if ((int) crc.getValue() != storedCrc) {
                throw new IllegalStateException("CRC mismatch in stash: " + path);
            }
            byte[] itemBytes = new byte[itemLength];
            System.arraycopy(all, 10, itemBytes, 0, itemLength);
            ItemStack item = ItemStack.deserializeBytes(itemBytes);
            return Optional.of(new StashEntry(slotIndex, item));
        } catch (IOException e) {
            throw new IllegalStateException("failed to load stash for " + playerUuid, e);
        }
    }

    boolean deleteSync(UUID playerUuid) {
        Path path = stashDir.resolve(playerUuid + ".stash");
        try {
            return Files.deleteIfExists(path);
        } catch (IOException e) {
            throw new IllegalStateException("failed to delete stash for " + playerUuid, e);
        }
    }
}
