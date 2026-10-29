package net.okitsu.ysmepicfightcompat.config;

import com.google.gson.*;

import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraftforge.fml.loading.FMLPaths;
import net.okitsu.ysmepicfightcompat.CompatMod;
import net.okitsu.ysmepicfightcompat.animation.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Atomic client-local rule/resource reload. Missing config uses the default Epic Fight policy. */
public final class MotionRulesClient {
    public record Snapshot(
            long revision, MotionRuleSet rules, Map<String, AnimationClip> animations,
            Map<String, String> resourceDigests) {
        public Snapshot {
            animations = Map.copyOf(animations);
            resourceDigests = Map.copyOf(resourceDigests);
        }

        public Snapshot(long revision, MotionRuleSet rules, Map<String, AnimationClip> animations) {
            this(revision, rules, animations, Map.of());
        }
    }

    private static final MotionRuleSet DEFAULT_RULES = MotionRuleSet.parse(new JsonObject());
    private static final ReloadState state = new ReloadState();
    private static final Map<UUID, String> diagnostics =
            Collections.synchronizedMap(new WeakHashMap<>());

    private MotionRulesClient() {}

    public static Snapshot snapshot() {
        return state.snapshot();
    }

    public static String error() {
        return state.error();
    }

    public static void remember(UUID entity, String text) {
        diagnostics.put(entity, text);
    }

    public static String diagnostic(UUID entity) {
        return diagnostics.getOrDefault(
                entity, "No converted-model motion frame has been evaluated.");
    }

    public static boolean reload(ResourceManager resources) {
        Path path = FMLPaths.CONFIGDIR.get().resolve("ysm_epicfight_compat/motion_rules.json");
        boolean accepted = state.reload(path, resources);
        if (accepted) diagnostics.clear();
        return accepted;
    }

    /** Isolated transaction state also allows reload behavior to be verified without a game. */
    static final class ReloadState {
        private volatile Snapshot current = new Snapshot(0, DEFAULT_RULES, Map.of());
        private volatile String error = "";

        Snapshot snapshot() {
            return current;
        }

        String error() {
            return error;
        }

        synchronized boolean reload(Path path, ResourceManager resources) {
            try {
                Snapshot next = readSnapshot(path, resources, current.revision() + 1);
                current = next;
                error = "";
                return true;
            } catch (IOException | RuntimeException ex) {
                error = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
                CompatMod.LOG.error(
                        "Motion rule reload rejected; retaining the last valid snapshot: {}", error);
                return false;
            }
        }
    }

    private static Snapshot readSnapshot(Path path, ResourceManager resources, long revision)
            throws IOException {
        MotionRuleSet rules =
                Files.exists(path, LinkOption.NOFOLLOW_LINKS)
                        ? readRules(path)
                        : DEFAULT_RULES;
        var animations = new LinkedHashMap<String, AnimationClip>();
        var resourceDigests = new LinkedHashMap<String, String>();
        int total = 0;
        var assets =
                resources.listResources(
                        "motion_animations", id -> id.getPath().endsWith(".json"));
        if (assets.size() > 256) throw new IOException("Too many external animation assets");
        for (var entry : assets.entrySet()) {
            byte[] bytes;
            try (var input = entry.getValue().open()) {
                bytes = bounded(input, 1024 * 1024);
            }
            total += bytes.length;
            if (total > 16 * 1024 * 1024)
                throw new IOException("External animation assets exceed 16 MiB");
            try {
                resourceDigests.put(entry.getKey().toString(), HexFormat.of().formatHex(
                        java.security.MessageDigest.getInstance("SHA-256").digest(bytes)));
            } catch (java.security.NoSuchAlgorithmException impossible) {
                throw new IllegalStateException("SHA-256 is unavailable", impossible);
            }
            var root =
                    JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8))
                            .getAsJsonObject();
            if (!root.has("animations") || !root.get("animations").isJsonObject())
                throw new IOException("Missing animations object: " + entry.getKey());
            for (var clip : root.getAsJsonObject("animations").entrySet()) {
                if (animations.size() >= 4096)
                    throw new IOException("Too many external animation clips");
                String id = entry.getKey().getNamespace() + ":" + clip.getKey();
                if (animations.containsKey(id))
                    throw new IOException("Duplicate external clip: " + id);
                animations.put(
                        id,
                        BedrockAnimationParser.parse(id, clip.getValue().getAsJsonObject()));
            }
        }
        return new Snapshot(revision, rules, animations, resourceDigests);
    }

    private static MotionRuleSet readRules(Path path) throws IOException {
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Rules must be a regular file");
        byte[] bytes;
        try (var input = Files.newInputStream(path)) {
            bytes = bounded(input, 256 * 1024);
        }
        return MotionRuleSet.parse(
                JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8))
                        .getAsJsonObject());
    }

    private static byte[] bounded(InputStream input, int limit) throws IOException {
        byte[] b = input.readNBytes(limit + 1);
        if (b.length > limit) throw new IOException("Animation/rule input exceeds size limit");
        return b;
    }
}
