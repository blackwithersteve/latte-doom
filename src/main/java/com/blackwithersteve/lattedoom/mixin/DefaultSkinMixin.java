package com.blackwithersteve.lattedoom.mixin;

import com.mojang.authlib.GameProfile;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.world.entity.player.PlayerSkin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.UUID;

/**
 * Dev-rig cosmetics: offline usernames hash into one of NINE default skins — with
 * -Dlattedoom.steveskin=true (both play.bat instances) every default resolves to
 * classic Steve. Inert without the flag; real (online) skins are untouched either way.
 */
@Mixin(DefaultPlayerSkin.class)
public abstract class DefaultSkinMixin {

    private static final boolean FORCE_STEVE = System.getProperty("lattedoom.steveskin") != null;

    @Inject(method = "get(Ljava/util/UUID;)Lnet/minecraft/world/entity/player/PlayerSkin;",
        at = @At("HEAD"), cancellable = true)
    private static void lattedoom$steveByUuid(UUID uuid, CallbackInfoReturnable<PlayerSkin> cir) {
        if (FORCE_STEVE) {
            cir.setReturnValue(DefaultPlayerSkin.getDefaultSkin());
        }
    }

    @Inject(method = "get(Lcom/mojang/authlib/GameProfile;)Lnet/minecraft/world/entity/player/PlayerSkin;",
        at = @At("HEAD"), cancellable = true)
    private static void lattedoom$steveByProfile(GameProfile profile,
                                                 CallbackInfoReturnable<PlayerSkin> cir) {
        if (FORCE_STEVE) {
            cir.setReturnValue(DefaultPlayerSkin.getDefaultSkin());
        }
    }
}
