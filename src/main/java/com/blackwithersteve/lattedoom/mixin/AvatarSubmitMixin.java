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
 * A transformed player is not drawn as a Minecraft avatar: the vanilla submit is
 * cancelled and a billboard built from the WAD's own {@code PLAY} sprites is submitted
 * instead. This covers both the third-person view of oneself and every other transformed
 * player in the world.
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
        // Players standing in a level are lit by that level's sectors, like the monsters,
        // rather than by the sky of the dimension the level is rendered in.
        final int levelLight = LatteWorld.levelLightCoords(avatar.x, avatar.y, avatar.z);
        if (levelLight >= 0) {
            avatar.lightCoords = levelLight;
        }
        final boolean marine;
        if (mc.player != null && avatar.id == mc.player.getId()) {
            marine = LatteWorld.marineForm(); // local state, no server round-trip
        } else {
            // Other players: the server-synchronised roster decides what this client sees.
            marine = mc.level != null
                && mc.level.getEntity(avatar.id) instanceof net.minecraft.world.entity.player.Player pl
                && com.blackwithersteve.lattedoom.play.MarineRoster.CLIENT.contains(pl.getUUID());
        }
        // Without a WAD on this client there is no marine art, so the vanilla avatar stays
        // visible rather than being cancelled into an invisible player. The join-time
        // notice explains why.
        if (marine && LatteWorld.spritesReady()) {
            MarineBody.submit(avatar, pose, collector, camera);
            ci.cancel();
        }
    }
}
