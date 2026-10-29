package net.okitsu.ysmepicfightcompat.event;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.Commands;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.settings.KeyConflictContext;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.okitsu.ysmepicfightcompat.CompatMod;
import net.okitsu.ysmepicfightcompat.diagnostics.IssueReportCapture;
import org.lwjgl.glfw.GLFW;

/** A player bookmark, requiring neither server permissions nor diagnostic mode. */
@Mod.EventBusSubscriber(modid = CompatMod.MOD_ID, value = Dist.CLIENT)
public final class IssueReportEvents {
    private static final KeyMapping REPORT = new KeyMapping("key.ysm_epicfight_compat.report",
            KeyConflictContext.IN_GAME, InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F9,
            "key.categories.ysm_epicfight_compat");

    private IssueReportEvents() {}

    @SubscribeEvent
    public static void commands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("ysmcompat")
                .then(Commands.literal("report")
                        .executes(context -> IssueReportCapture.request(""))
                        .then(Commands.argument("reason", StringArgumentType.greedyString())
                                .executes(context -> IssueReportCapture.request(
                                        StringArgumentType.getString(context, "reason"))))));
    }

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        while (REPORT.consumeClick()) {
            if (Minecraft.getInstance().screen == null) IssueReportCapture.request("");
        }
    }

    @SubscribeEvent
    public static void frame(TickEvent.RenderTickEvent event) {
        if (event.phase == TickEvent.Phase.END) IssueReportCapture.endFrame();
    }

    @Mod.EventBusSubscriber(modid = CompatMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static final class Registration {
        @SubscribeEvent
        public static void register(RegisterKeyMappingsEvent event) { event.register(REPORT); }
    }
}
