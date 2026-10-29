package net.okitsu.ysmepicfightcompat.animation;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.LivingEntity;

import yesman.epicfight.api.animation.types.DynamicAnimation;
import yesman.epicfight.api.animation.types.LinkAnimation;
import yesman.epicfight.world.capabilities.EpicFightCapabilities;
import yesman.epicfight.world.capabilities.entitypatch.LivingEntityPatch;
import yesman.epicfight.world.capabilities.entitypatch.player.PlayerPatch;

import java.util.*;

/** Read-only facts; attack state is input data rather than an ownership decision. */
public final class MotionRuleFacts {
    public record Clock(double elapsed, double duration) {}

    public record Sample(MotionRuleSet.Facts facts, Map<String, Clock> clocks) {}

    private MotionRuleFacts() {}

    public static Sample capture(LivingEntity entity, String model, boolean firstPerson) {
        var values = new LinkedHashMap<String, String>();
        var animations = new LinkedHashSet<String>();
        var clocks = new LinkedHashMap<String, Clock>();
        values.put("model", model);
        values.put("view", firstPerson ? "first_person" : "third_person");
        var movement = MovementAnimationType.resolve(entity);
        values.put("movement", movement == null ? "idle" : movement.configKey());
        values.put(
                "item",
                BuiltInRegistries.ITEM.getKey(entity.getMainHandItem().getItem()).toString());
        values.put("using_item", String.valueOf(entity.isUsingItem()));
        values.put("sneaking", String.valueOf(entity.isShiftKeyDown()));
        values.put("sprinting", String.valueOf(entity.isSprinting()));
        values.put("on_ground", String.valueOf(entity.onGround()));
        values.put("in_water", String.valueOf(entity.isInWater()));
        LivingEntityPatch<?> patch =
                EpicFightCapabilities.getEntityPatch(entity, LivingEntityPatch.class);
        var state = patch == null ? null : patch.getEntityState();
        values.put("attacking", String.valueOf(state != null && state.attacking()));
        values.put("hurt", String.valueOf(entity.hurtTime > 0 || state != null && state.hurt()));
        boolean battle =
                patch instanceof PlayerPatch<?> playerPatch
                        ? playerPatch.isEpicFightMode()
                        : patch != null && patch.overrideRender();
        values.put("battle_mode", String.valueOf(battle));
        if (patch != null && patch.getClientAnimator() != null)
            patch.getClientAnimator()
                    .iterVisibleLayersUntilFalse(
                            layer -> {
                                var player = layer.animationPlayer;
                                if (player != null && !player.isEmpty()) {
                                    var accessor = player.getAnimation();
                                    String id =
                                            accessor.registryName() == null
                                                    ? ""
                                                    : accessor.registryName().toString();
                                    if (!id.isBlank()) animations.add(id);
                                    DynamicAnimation animation = accessor.get();
                                    if (animation != null) {
                                        var real = animation.getRealAnimation();
                                        if (real != null
                                                && !real.isEmpty()
                                                && real.registryName() != null)
                                            animations.add(real.registryName().toString());
                                        if (!(animation instanceof LinkAnimation) && !id.isBlank())
                                            clocks.putIfAbsent(
                                                    id,
                                                    new Clock(
                                                            player.getElapsedTime(),
                                                            animation.getTotalTime()));
                                    }
                                }
                                var next = layer.getNextAnimation();
                                if (next != null && !next.isEmpty() && next.registryName() != null)
                                    animations.add(next.registryName().toString());
                                return true;
                            });
        return new Sample(new MotionRuleSet.Facts(values, animations), Map.copyOf(clocks));
    }
}
