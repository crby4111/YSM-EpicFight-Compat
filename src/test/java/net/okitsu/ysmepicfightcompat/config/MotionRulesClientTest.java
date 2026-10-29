package net.okitsu.ysmepicfightcompat.config;

import net.minecraft.server.packs.resources.ResourceManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MotionRulesClientTest {
    @TempDir Path directory;

    @Test
    void absentConfigurationUsesEpicFightWithoutWritingAFile() {
        var state = new MotionRulesClient.ReloadState();
        Path file = directory.resolve("motion_rules.json");
        assertTrue(state.snapshot().rules().enabled());
        assertTrue(state.reload(file, resources(false)));
        assertTrue(state.snapshot().rules().enabled());
        assertTrue(state.snapshot().rules().overrides().isEmpty());
        assertTrue(state.snapshot().rules().extras().isEmpty());
        assertEquals(1, state.snapshot().revision());
        assertFalse(Files.exists(file));
    }

    @Test
    void explicitDisableStillRestoresLegacyPolicy() throws Exception {
        Path file = config("{\"enabled\":false}");
        var state = new MotionRulesClient.ReloadState();
        assertTrue(state.reload(file, resources(false)));
        assertFalse(state.snapshot().rules().enabled());
    }

    @Test
    void invalidFirstLoadKeepsSafeEpicFightDefaults() throws Exception {
        Path file = config("{invalid");
        var state = new MotionRulesClient.ReloadState();
        var defaults = state.snapshot();
        assertFalse(state.reload(file, resources(false)));
        assertSame(defaults, state.snapshot());
        assertTrue(state.snapshot().rules().enabled());
        assertFalse(state.error().isBlank());
    }

    @Test
    void failedRuleAndAssetReloadsRetainTheEntireLastValidSnapshot() throws Exception {
        Path file = config("{\"enabled\":false}");
        var state = new MotionRulesClient.ReloadState();
        assertTrue(state.reload(file, resources(false)));
        var previous = state.snapshot();

        Files.writeString(file, "{invalid");
        assertFalse(state.reload(file, resources(false)));
        assertSame(previous, state.snapshot());

        Files.writeString(file, "{\"enabled\":true}");
        assertFalse(state.reload(file, resources(true)));
        assertSame(previous, state.snapshot());
        assertEquals("fixture asset failure", state.error());

        assertTrue(state.reload(file, resources(false)));
        assertEquals(previous.revision() + 1, state.snapshot().revision());
        assertTrue(state.snapshot().rules().enabled());
        assertEquals("", state.error());
    }

    @Test
    void oversizedAndNonFileConfigurationsAreRejectedWithoutReplacingState() throws Exception {
        Path file = config(" ".repeat(256 * 1024 + 1));
        var state = new MotionRulesClient.ReloadState();
        var previous = state.snapshot();
        assertFalse(state.reload(file, resources(false)));
        assertSame(previous, state.snapshot());
        assertFalse(state.reload(directory, resources(false)));
        assertSame(previous, state.snapshot());
    }

    private Path config(String text) throws Exception {
        Path file = directory.resolve("motion_rules.json");
        Files.writeString(file, text);
        return file;
    }

    private static ResourceManager resources(boolean fail) {
        return (ResourceManager) Proxy.newProxyInstance(
                ResourceManager.class.getClassLoader(), new Class<?>[] {ResourceManager.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("listResources")) {
                        if (fail) throw new IllegalStateException("fixture asset failure");
                        return Map.of();
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }
}
