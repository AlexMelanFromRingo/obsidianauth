package org.alex_melan.obsidianauth.paper;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

/**
 * Base class for MockBukkit-backed integration tests.
 *
 * <p>Spins up a fresh {@link ServerMock} per test. Subclasses build the specific
 * listeners / services they exercise directly (rather than going through the full
 * {@code ObsidianAuthPaperPlugin.onEnable}, which would require a real DB + key file) —
 * this keeps each test focused and fast while still exercising the real Bukkit event bus.
 */
public abstract class IntegrationTestBase {

    protected ServerMock server;

    @BeforeEach
    void setUpServer() {
        server = MockBukkit.mock();
    }

    @AfterEach
    void tearDownServer() {
        MockBukkit.unmock();
    }
}
