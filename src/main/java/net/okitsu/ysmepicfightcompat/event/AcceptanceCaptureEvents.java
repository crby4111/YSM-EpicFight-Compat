package net.okitsu.ysmepicfightcompat.event;

import com.google.gson.GsonBuilder;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.okitsu.ysmepicfightcompat.CompatMod;
import net.okitsu.ysmepicfightcompat.config.MotionRulesClient;
import net.okitsu.ysmepicfightcompat.diagnostics.FrameTimingCapture;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.LinkedHashMap;

/** Explicit, local-only acceptance capture. Inactive during ordinary play. */
@Mod.EventBusSubscriber(modid = CompatMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE,
        value = Dist.CLIENT)
public final class AcceptanceCaptureEvents {
    private static FrameTimingCapture active;
    private static String label;
    private static int seconds;
    private static long revision;
    private static boolean rulesEnabled;

    private AcceptanceCaptureEvents() {}

    @SubscribeEvent
    public static void commands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("ysmcompat")
                .then(Commands.literal("bench")
                        .then(Commands.literal("start")
                                .then(Commands.argument("label", StringArgumentType.word())
                                        .executes(context -> start(StringArgumentType.getString(context, "label"), 180))
                                        .then(Commands.argument("seconds", IntegerArgumentType.integer(30, 600))
                                                .executes(context -> start(StringArgumentType.getString(context, "label"),
                                                        IntegerArgumentType.getInteger(context, "seconds"))))))
                        .then(Commands.literal("stop").executes(context -> finish(true)))
                        .then(Commands.literal("status").executes(context -> {
                            message(active == null ? "No frame capture is active."
                                    : "Frame capture " + label + ": " + active.samples() + " samples.");
                            return active == null ? 0 : 1;
                        }))));
    }

    private static int start(String name, int duration) {
        var minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null || active != null) {
            message("Enter a world and finish any active capture first.");
            return 0;
        }
        if (!name.matches("[A-Za-z0-9_-]{1,64}")) {
            message("Use a 1-64 character label containing letters, digits, _ or -.");
            return 0;
        }
        label = name;
        seconds = duration;
        var rules = MotionRulesClient.snapshot();
        revision = rules.revision();
        rulesEnabled = rules.rules().enabled();
        active = new FrameTimingCapture(System.nanoTime(), 60, duration);
        message("Frame capture armed: 60s warm-up, then " + duration
                + "s sampling. Close chat; keep the scene/settings fixed. Menus or rule reload invalidate the run.");
        return 1;
    }

    @SubscribeEvent
    public static void frame(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.END || active == null) return;
        var minecraft = Minecraft.getInstance();
        boolean stable = minecraft.level != null && minecraft.player != null
                && minecraft.screen == null && !minecraft.isPaused()
                && MotionRulesClient.snapshot().revision() == revision;
        active.frame(System.nanoTime(), stable);
        if (active.complete()) finish(false);
    }

    private static int finish(boolean stoppedEarly) {
        if (active == null) return 0;
        var result = active.result(stoppedEarly);
        active = null;
        var minecraft = Minecraft.getInstance();
        try {
            var base = minecraft.gameDirectory.toPath().resolve("ysmcompat-acceptance");
            Files.createDirectories(base);
            var directory = Files.createTempDirectory(base, label + "-");
            var report = new LinkedHashMap<String, Object>();
            report.put("schema", 1);
            report.put("recorded_at", Instant.now().toString());
            report.put("label", label);
            report.put("warmup_seconds", 60);
            report.put("requested_sample_seconds", seconds);
            report.put("rules_revision", revision);
            report.put("rules_enabled", rulesEnabled);
            report.put("width", minecraft.getWindow().getWidth());
            report.put("height", minecraft.getWindow().getHeight());
            report.put("java", System.getProperty("java.version"));
            report.put("os", System.getProperty("os.name"));
            report.put("available_processors", Runtime.getRuntime().availableProcessors());
            report.put("max_heap_bytes", Runtime.getRuntime().maxMemory());
            report.put("measurement", "Intervals between client render-end events; not isolated GPU timings");
            report.put("result", result);
            Files.writeString(directory.resolve("frame-times.json"),
                    new GsonBuilder().setPrettyPrinting().create().toJson(report), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW);
            message("Frame capture saved (valid=" + result.valid() + "): " + directory);
            return 1;
        } catch (IOException | RuntimeException exception) {
            CompatMod.LOG.error("Cannot save acceptance frame capture", exception);
            message("Frame capture could not be saved: " + exception.getMessage());
            return 0;
        }
    }

    private static void message(String value) {
        var player = Minecraft.getInstance().player;
        if (player != null) player.displayClientMessage(Component.literal(value), false);
    }
}
