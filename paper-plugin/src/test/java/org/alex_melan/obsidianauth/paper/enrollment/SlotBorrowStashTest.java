package org.alex_melan.obsidianauth.paper.enrollment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import org.alex_melan.obsidianauth.core.async.ImmediateAsyncExecutor;
import org.alex_melan.obsidianauth.paper.IntegrationTestBase;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Integration test for {@link SlotBorrowStash}. Uses MockBukkit because {@code
 * ItemStack.serializeAsBytes()} requires a running Bukkit instance.
 */
class SlotBorrowStashTest extends IntegrationTestBase {

    private final ImmediateAsyncExecutor async = new ImmediateAsyncExecutor();

    @Test
    void saveThenLoad_roundTrips(@TempDir Path tmp) {
        SlotBorrowStash stash = new SlotBorrowStash(async, tmp);
        UUID uuid = UUID.randomUUID();
        ItemStack original = new ItemStack(Material.DIAMOND_PICKAXE, 1);

        stash.saveSync(uuid, 3, original);
        Optional<SlotBorrowStash.StashEntry> loaded = stash.loadSync(uuid);

        assertThat(loaded).isPresent();
        assertThat(loaded.get().slotIndex()).isEqualTo(3);
        assertThat(loaded.get().item().getType()).isEqualTo(Material.DIAMOND_PICKAXE);
    }

    @Test
    void load_missingReturnsEmpty(@TempDir Path tmp) {
        SlotBorrowStash stash = new SlotBorrowStash(async, tmp);
        assertThat(stash.loadSync(UUID.randomUUID())).isEmpty();
    }

    @Test
    void delete_isIdempotent(@TempDir Path tmp) {
        SlotBorrowStash stash = new SlotBorrowStash(async, tmp);
        UUID uuid = UUID.randomUUID();
        stash.saveSync(uuid, 0, new ItemStack(Material.STONE, 64));
        assertThat(stash.deleteSync(uuid)).isTrue();
        assertThat(stash.deleteSync(uuid)).isFalse();
    }

    @Test
    void load_rejectsBadMagic(@TempDir Path tmp) throws Exception {
        SlotBorrowStash stash = new SlotBorrowStash(async, tmp);
        UUID uuid = UUID.randomUUID();
        Files.write(tmp.resolve(uuid + ".stash"), "NOTAVALIDSTASHFILE!!".getBytes());
        assertThatThrownBy(() -> stash.loadSync(uuid))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("magic");
    }

    @Test
    void load_rejectsCrcMismatch(@TempDir Path tmp) throws Exception {
        SlotBorrowStash stash = new SlotBorrowStash(async, tmp);
        UUID uuid = UUID.randomUUID();
        stash.saveSync(uuid, 2, new ItemStack(Material.STONE, 1));

        // Corrupt one byte in the item payload region (offset 10+).
        Path file = tmp.resolve(uuid + ".stash");
        byte[] bytes = Files.readAllBytes(file);
        bytes[11] ^= 0x01;
        Files.write(file, bytes);

        assertThatThrownBy(() -> stash.loadSync(uuid))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CRC");
    }

    @Test
    void save_rejectsSlotOutOfRange(@TempDir Path tmp) {
        SlotBorrowStash stash = new SlotBorrowStash(async, tmp);
        assertThatThrownBy(() -> stash.saveSync(UUID.randomUUID(), 9, new ItemStack(Material.STONE)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
