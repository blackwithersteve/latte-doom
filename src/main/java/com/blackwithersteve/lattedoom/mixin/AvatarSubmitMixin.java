package com.blackwithersteve.lattedoom.mixin;

import com.blackwithersteve.lattedoom.render.LatteWorld;
import com.blackwithersteve.lattedoom.render.MarineBody;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A transformed player is not rendered as a player at all: the vanilla avatar submit is
 * cancelled and the WAD's PLAY marine billboard goes out instead (F5 + other players).
 */
@Mixin(LivingEntityRenderer.class)
public abstract class AvatarSubmitMixin {

    @Inject(
        method = "submit(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",
        at = @At("HEAD"),
        cancellable = true
    )
    private void lattedoom$submitAsMarine(LivingEntityRenderState state, PoseStack pose,
                                          SubmitNodeCollector collector, CameraRenderState camera,
                                          CallbackInfo ci) {
        if (!(state instanceof AvatarRenderState avatar)) {
            return;
        }
        final Minecraft mc = Minecraft.getInstance();
        // players standing in the level are lit by ITS sectors, like the monsters —
        // not by the open sky the level floats in
        final int levelLight = LatteWorld.levelLightCoords(avatar.x, avatar.y, avatar.z);
        if (levelLight >= 0) {
            avatar.lightCoords = levelLight;
        }
        final boolean marine;
        if (mc.player != null && avatar.id == mc.player.getId()) {
            marine = LatteWorld.marineForm(); // self: instant, no server round-trip
        } else {
            // other players: the server-synced roster decides what this client sees
            marine = mc.level != null
                && mc.level.getEntity(avatar.id) instanceof net.minecraft.world.entity.player.Player pl
                && com.blackwithersteve.lattedoom.play.MarineRoster.CLIENT.contains(pl.getUUID());
        }
        // no WAD on this client = no marine art: leave Steve visible (with the join-time
        // disclaimer explaining why) instead of cancelling vanilla into an invisible player
        if (marine && LatteWorld.spritesReady()) {
            MarineBody.submit(avatar, pose, collector, camera);
            ci.cancel();
        }
    }
}
