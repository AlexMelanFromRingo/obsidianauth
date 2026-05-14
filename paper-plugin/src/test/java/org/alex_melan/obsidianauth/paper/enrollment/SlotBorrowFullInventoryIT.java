package org.alex_melan.obsidianauth.paper.enrollment;

import static org.assertj.core.api.Assertions.assertThat;

import be.seeseemelk.mockbukkit.entity.PlayerMock;
import java.nio.file.Files;
import java.nio.file.Path;
import org.alex_melan.obsidianauth.paper.IntegrationTestBase;
import org.alex_melan.obsidianauth.paper.session.PaperSession;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * US1 slot-borrow path: when no hotbar slot is free, the QR card borrows the
 * currently-selected slot, the displaced item is persisted to the on-disk stash, and a
 * successful verification restores the original item and deletes the stash file.
 */
class SlotBorrowFullInventoryIT extends IntegrationTestBase {

    private static final int HELD_SLOT = 4;

    @Test
    void fullInventory_borrowsHeldSlotAndRestoresOnVerify(@TempDir Path tmp) throws Exception {
        try (EnrollmentHarness h = new EnrollmentHarness(tmp)) {
            PlayerMock player = server.addPlayer();
            PlayerInventory inv = player.getInventory();

            // Fill the entire main inventory + offhand so no hotbar slot is free.
            for (int i = 0; i < 36; i++) {
                inv.setItem(i, new ItemStack(Material.STONE, 1));
            }
            inv.setItemInOffHand(new ItemStack(Material.STONE, 1));
            // The currently-held slot carries a distinctive item we can track through the stash.
            inv.setItem(HELD_SLOT, new ItemStack(Material.DIAMOND, 1));
            inv.setHeldItemSlot(HELD_SLOT);

            PaperSession session = new PaperSession(
                    player.getUniqueId(), player.getLocation(), PaperSession.State.PENDING_ENROLLMENT);
            h.registry.register(session);

            // --- Join → enrollment: card must borrow the held slot ---
            h.orchestrator.startEnrollment(player, session).join();

            assertThat(inv.getItem(HELD_SLOT)).isNotNull();
            assertThat(inv.getItem(HELD_SLOT).getType()).isEqualTo(Material.FILLED_MAP);
            // The displaced diamond is persisted to the per-player stash file.
            Path stashFile = h.stashDir.resolve(player.getUniqueId() + ".stash");
            assertThat(Files.exists(stashFile)).isTrue();

            // --- Verify the code: original item restored, stash file deleted ---
            String code = h.currentCodeFor(player.getUniqueId());
            h.verification.verify(player, session, code).join();

            assertThat(session.state()).isEqualTo(PaperSession.State.AUTHED);
            assertThat(inv.getItem(HELD_SLOT)).isNotNull();
            assertThat(inv.getItem(HELD_SLOT).getType()).isEqualTo(Material.DIAMOND);
            assertThat(Files.exists(stashFile)).isFalse();
        }
    }
}
