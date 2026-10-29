package net.okitsu.ysmepicfightcompat.diagnostics;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.network.chat.Component;
import net.minecraftforge.fml.ModList;
import net.okitsu.ysmepicfightcompat.CompatMod;
import net.okitsu.ysmepicfightcompat.animation.MotionRuleFacts;
import net.okitsu.ysmepicfightcompat.animation.ParallelAnimationProgram;
import net.okitsu.ysmepicfightcompat.config.ClientPreferences;
import net.okitsu.ysmepicfightcompat.config.MotionRulesClient;
import net.okitsu.ysmepicfightcompat.mesh.AuxiliaryBoneLayout;
import net.okitsu.ysmepicfightcompat.render.RenderFrameContext;
import org.joml.Matrix4f;
import yesman.epicfight.api.utils.math.OpenMatrix4f;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Explicit local capture of an actual draw, never a second animation evaluation. */
public final class IssueReportCapture {
    private static final int MAX_POSES = AuxiliaryBoneLayout.MAX_TOTAL_POSES;
    private static final long TIMEOUT_NANOS = 5_000_000_000L;
    private static final AtomicBoolean WRITING = new AtomicBoolean();
    private static final java.util.concurrent.ExecutorService WRITER = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "ysmcompat-issue-report");
        thread.setDaemon(true);
        return thread;
    });
    // Requested and consumed on the client/render thread. No game references reach WRITER.
    private static Pending pending;
    private static long lastRequest;

    private static final class Pending {
        final UUID player;
        final Object level;
        final long requested = System.nanoTime();
        final Map<String, Object> data = new LinkedHashMap<>();
        Map<String, Object> draw;

        Pending(Minecraft minecraft, String reason) {
            player = minecraft.player.getUUID();
            level = minecraft.level;
            data.put("schema", 1);
            data.put("recorded_at", Instant.now().toString());
            data.put("reason", reason.substring(0, Math.min(256, reason.length())));
            data.put("scope", "First eligible local compat body draw after request; matrices are that draw, not a unique final screen pose or deterministic animation replay");
            data.put("missing_replay_state", List.of("complete_queries", "variables",
                    "controller_stack", "physics_history", "rng_state",
                    "shadow_and_multipass_render_state"));
            data.put("versions", versions());
            data.put("requested_tick", minecraft.player.tickCount);
        }
    }

    private IssueReportCapture() {}

    public static boolean wantsFrame(RenderFrameContext.Frame frame) {
        return pending != null && pending.draw == null && frame != null
                && !frame.renderingInInventory() && pending.player.equals(frame.entity().getUUID());
    }

    public static int request(String reason) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null) {
            message("world_required");
            return 0;
        }
        long now = System.nanoTime();
        if (pending != null || WRITING.get() || lastRequest != 0 && now - lastRequest < 3_000_000_000L) {
            message("busy");
            return 0;
        }
        lastRequest = now;
        pending = new Pending(minecraft, reason);
        message("armed");
        return 1;
    }

    /** Called for the first eligible primary body draw, before scratch matrices can be reused. */
    public static void draw(RenderFrameContext.Frame frame, String model,
                            AuxiliaryBoneLayout layout, OpenMatrix4f[] epicInput,
                            OpenMatrix4f[] finalSkin, ParallelAnimationProgram.Frame animation,
                            float partialTick, float horizontalScale, float verticalScale,
                            float meshScale, Matrix4f outerPose, String texture,
                            java.util.Set<String> hiddenDrawBones) {
        Pending capture = pending;
        if (capture == null || capture.draw != null || frame == null
                || frame.renderingInInventory() || !capture.player.equals(frame.entity().getUUID())) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level != capture.level || minecraft.screen != null) return;
        try {
            var draw = new LinkedHashMap<String, Object>();
            draw.put("model", model);
            draw.put("capture_selection", "first_eligible_local_compat_body_draw_in_render_frame");
            draw.put("texture", texture);
            draw.put("tick", frame.entity().tickCount);
            draw.put("partial_tick", partialTick);
            draw.put("first_person", frame.firstPerson());
            draw.put("camera", minecraft.options.getCameraType().name());
            draw.put("epic_action_active", frame.epicFightActionActive());
            draw.put("epic_model_yaw", frame.epicModelYaw());
            draw.put("ysm_movement", frame.ysmMovement() == null ? null : frame.ysmMovement().configKey());
            draw.put("replaces_epic_pose", animation != null && animation.replaceEpicFightPose());
            draw.put("movement_pose_key", animation == null ? null : animation.movementPoseKey());
            draw.put("hidden_bones", animation == null ? List.of() : List.copyOf(animation.hiddenBones()));
            draw.put("hidden_draw_bones", List.copyOf(hiddenDrawBones));
            draw.put("visible_parts", frame.visibleParts());
            draw.put("show_unlisted_parts", frame.showUnlistedParts());
            draw.put("skin_space", "actual draw palette; first-person basis may differ from canonical model space");
            draw.put("epic_input", IssuePoseSnapshot.matrices(epicInput, MAX_POSES));
            draw.put("final_skin", IssuePoseSnapshot.matrices(finalSkin, MAX_POSES));
            draw.put("bones", IssuePoseSnapshot.bones(layout));
            draw.put("model_scale", new float[]{horizontalScale, verticalScale, horizontalScale});
            draw.put("outer_pose_before_mesh_scale", IssuePoseSnapshot.matrix(outerPose));
            draw.put("mesh_draw_scale", meshScale);
            var view = frame.fullBodyPoseTransform();
            draw.put("available_first_person_basis", view == null ? null : IssuePoseSnapshot.matrix(view));
            var facts = MotionRuleFacts.capture(frame.entity(), model, frame.firstPerson());
            draw.put("observed_facts_after_composition", facts.facts());
            draw.put("observed_animation_clocks", facts.clocks());
            draw.put("entity_pitch", frame.entity().getXRot());
            draw.put("entity_yaw", frame.entity().getYRot());
            draw.put("swimming", frame.entity().isSwimming());
            draw.put("visually_swimming", frame.entity().isVisuallySwimming());
            draw.put("fall_flying", frame.entity().isFallFlying());
            var rules = MotionRulesClient.snapshot();
            draw.put("rules_revision", rules.revision());
            draw.put("rules", JsonParser.parseString(rules.rules().sourceJson()));
            draw.put("animation_resource_sha256", rules.resourceDigests());
            draw.put("rule_diagnostic", MotionRulesClient.diagnostic(capture.player));
            capture.draw = draw;
        } catch (RuntimeException exception) {
            capture.data.put("pose_capture_error", exception.getClass().getSimpleName());
            capture.draw = Map.of("status", "capture_failed");
            CompatMod.LOG.warn("YSM-EF Compat: issue pose capture failed", exception);
        }
    }

    /** Render END: the screenshot is frame-end; matrices remain tied to the selected body draw. */
    public static void endFrame() {
        Pending capture = pending;
        if (capture == null) return;
        Minecraft minecraft = Minecraft.getInstance();
        boolean sameWorld = minecraft.level == capture.level && minecraft.player != null
                && capture.player.equals(minecraft.player.getUUID());
        if (!sameWorld) {
            pending = null;
            message("cancelled");
            return;
        }
        if (capture.draw == null && System.nanoTime() - capture.requested < TIMEOUT_NANOS) return;
        pending = null;
        capture.data.put("draw", capture.draw == null
                ? Map.of("status", "unavailable", "reason", "No local compat body draw within five seconds")
                : capture.draw);
        NativeImage screenshot = null;
        try {
            var target = minecraft.getMainRenderTarget();
            if (minecraft.screen != null) capture.data.put("screenshot", "skipped_menu_open");
            else if ((long) target.width * target.height > 8_400_000L)
                capture.data.put("screenshot", "skipped_pixel_limit");
            else {
                screenshot = Screenshot.takeScreenshot(target);
                capture.data.put("screenshot", "screenshot.png; captured at render end");
            }
        } catch (RuntimeException exception) {
            capture.data.put("screenshot", "capture_failed: " + exception.getClass().getSimpleName());
        }
        Path instance = minecraft.gameDirectory.toPath().toAbsolutePath().normalize();
        Path output;
        try {
            output = instance.resolve(ClientPreferences.ISSUE_REPORT_DIRECTORY.get()).normalize();
            if (output.toString().startsWith("\\\\"))
                throw new IllegalArgumentException("Issue reports require a local directory");
        } catch (RuntimeException exception) {
            if (screenshot != null) screenshot.close();
            message("failed");
            return;
        }
        var modFile = ModList.get().getModFileById(CompatMod.MOD_ID);
        Path jar = modFile == null ? null : modFile.getFile().getFilePath();
        NativeImage capturedImage = screenshot;
        Map<String, Object> detached = capture.data;
        WRITING.set(true);
        WRITER.execute(() -> write(detached, output, instance, jar, capturedImage));
    }

    private static void write(Map<String, Object> data, Path output, Path instance,
                              Path jar, NativeImage screenshot) {
        Path directory = null;
        try {
            Files.createDirectories(output);
            directory = Files.createTempDirectory(output, "issue-");
            if (jar != null && Files.isRegularFile(jar)) {
                data.put("mod_jar", jar.getFileName().toString());
                data.put("mod_jar_sha256", digest(jar));
            } else data.put("mod_jar_sha256", "unavailable (development classes or missing jar)");
            if (screenshot != null) {
                try { screenshot.writeToFile(directory.resolve("screenshot.png")); }
                catch (IOException | RuntimeException exception) {
                    data.put("screenshot", "write_failed: " + exception.getClass().getSimpleName());
                }
            }
            try {
                Files.writeString(directory.resolve("compat-log-tail.txt"),
                        compatLogTail(instance.resolve("logs/latest.log")), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE_NEW);
                data.put("log", "Own compatibility messages from last 256 KiB of latest.log; not a full game log");
            } catch (IOException exception) { data.put("log", "unavailable: " + exception.getClass().getSimpleName()); }
            String json = new GsonBuilder().serializeNulls().setPrettyPrinting().create().toJson(data);
            if (json.getBytes(StandardCharsets.UTF_8).length > 16 * 1024 * 1024)
                throw new IOException("Issue snapshot exceeds 16 MiB");
            Files.writeString(directory.resolve("issue.json"), json, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW);
            Path saved = directory;
            Minecraft.getInstance().execute(() -> message("saved", saved.toString()));
        } catch (Exception exception) {
            CompatMod.LOG.error("YSM-EF Compat: cannot save issue report to {}", directory == null ? output : directory, exception);
            Minecraft.getInstance().execute(() -> message("failed"));
        } finally {
            if (screenshot != null) screenshot.close();
            WRITING.set(false);
        }
    }

    static String compatLogTail(Path log) throws IOException {
        try (RandomAccessFile file = new RandomAccessFile(log.toFile(), "r")) {
            long length = file.length();
            long offset = Math.max(0, length - 256 * 1024);
            file.seek(offset);
            byte[] bytes = new byte[(int) (length - offset)];
            file.readFully(bytes);
            String tail = new String(bytes, StandardCharsets.UTF_8);
            if (offset > 0) {
                int line = tail.indexOf('\n');
                tail = line < 0 ? "" : tail.substring(line + 1);
            }
            return tail.lines().filter(line -> line.contains("YSM-EF Compat")
                    || line.contains("ysm_epicfight_compat") || line.contains("ysmepicfightcompat"))
                    .collect(java.util.stream.Collectors.joining("\n"));
        }
    }

    private static String digest(Path file) throws Exception {
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(file)) {
            byte[] buffer = new byte[65536];
            for (int count; (count = input.read(buffer)) >= 0;) sha.update(buffer, 0, count);
        }
        return HexFormat.of().formatHex(sha.digest());
    }

    private static Map<String, String> versions() {
        var versions = new LinkedHashMap<String, String>();
        for (String id : List.of(CompatMod.MOD_ID, "minecraft", "forge", "epicfight", "yes_steve_model", "ysm_mapping_api"))
            ModList.get().getModContainerById(id).ifPresent(container ->
                    versions.put(id, container.getModInfo().getVersion().toString()));
        return versions;
    }

    private static void message(String key, Object... values) {
        var player = Minecraft.getInstance().player;
        if (player != null) player.displayClientMessage(
                Component.translatable("message.ysm_epicfight_compat.report." + key, values), false);
    }
}
