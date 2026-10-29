package net.okitsu.ysmepicfightcompat.event;

import net.minecraft.client.Minecraft;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.okitsu.ysmepicfightcompat.CompatMod;
import net.okitsu.ysmepicfightcompat.config.MotionRulesClient;
import net.okitsu.ysmepicfightcompat.mesh.CombatMeshCache;
import net.okitsu.ysmepicfightcompat.render.PlayerSelectionResolver;

/** Client-local controls: does not require operator permissions or send server commands. */
@Mod.EventBusSubscriber(
        modid = CompatMod.MOD_ID,
        bus = Mod.EventBusSubscriber.Bus.FORGE,
        value = Dist.CLIENT)
public final class MotionRuleCommands {
    private MotionRuleCommands() {}

    @SubscribeEvent
    public static void register(RegisterClientCommandsEvent event) {
        event.getDispatcher()
                .register(
                        Commands.literal("ysmcompat")
                                .then(
                                        Commands.literal("rules")
                                                .then(
                                                        Commands.literal("reload")
                                                                .executes(
                                                                        context -> {
                                                                            boolean ok =
                                                                                    MotionRulesClient
                                                                                            .reload(
                                                                                                    Minecraft
                                                                                                            .getInstance()
                                                                                                            .getResourceManager());
                                                                            if (ok) {
                                                                                PlayerSelectionResolver
                                                                                        .clear();
                                                                                CombatMeshCache
                                                                                        .clear();
                                                                            }
                                                                            context.getSource()
                                                                                    .sendSuccess(
                                                                                            () ->
                                                                                                    Component
                                                                                                            .literal(
                                                                                                                    ok
                                                                                                                            ? "Motion"
                                                                                                                                  + " rules"
                                                                                                                                  + " reloaded."
                                                                                                                                  + " Mode:"
                                                                                                                                  + " "
                                                                                                                                    + (MotionRulesClient
                                                                                                                                                    .snapshot()
                                                                                                                                                    .rules()
                                                                                                                                                    .enabled()
                                                                                                                                            ? "user rules"
                                                                                                                                                  + " (default"
                                                                                                                                                  + " EF)"
                                                                                                                                            : "upstream"
                                                                                                                                                  + " fallback")
                                                                                                                            : "Motion"
                                                                                                                                  + " rules"
                                                                                                                                  + " rejected;"
                                                                                                                                  + " last"
                                                                                                                                  + " valid"
                                                                                                                                  + " configuration"
                                                                                                                                  + " retained:"
                                                                                                                                  + " "
                                                                                                                                    + MotionRulesClient
                                                                                                                                            .error()),
                                                                                            false);
                                                                            return ok ? 1 : 0;
                                                                        }))
                                                .then(
                                                        Commands.literal("status")
                                                                .executes(
                                                                        context -> {
                                                                            var player =
                                                                                    Minecraft
                                                                                            .getInstance()
                                                                                            .player;
                                                                            String text =
                                                                                    "Motion rule"
                                                                                        + " mode="
                                                                                            + MotionRulesClient
                                                                                                    .snapshot()
                                                                                                    .rules()
                                                                                                    .enabled()
                                                                                            + "; revision="
                                                                                            + MotionRulesClient
                                                                                                    .snapshot()
                                                                                                    .revision()
                                                                                            + "; reload"
                                                                                            + " error="
                                                                                            + MotionRulesClient
                                                                                                    .error();
                                                                            if (player != null)
                                                                                text +=
                                                                                        "; "
                                                                                                + MotionRulesClient
                                                                                                        .diagnostic(
                                                                                                                player
                                                                                                                        .getUUID());
                                                                            String result = text;
                                                                            context.getSource()
                                                                                    .sendSuccess(
                                                                                            () ->
                                                                                                    Component
                                                                                                            .literal(
                                                                                                                    result),
                                                                                            false);
                                                                            return 1;
                                                                        }))));
    }
}
