package com.blackwithersteve.lattedoom.mixin;

import com.blackwithersteve.lattedoom.play.MinecraftCombat;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TridentItem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Releasing a ranged weapon wakes the monsters that can hear it. The engine raises its own
 * alert whenever a DOOM weapon fires, but a Minecraft weapon is fired outside the engine, so
 * a player shooting a bow across a room would otherwise leave the level asleep.
 */
@Mixin(MultiPlayerGameMode.class)
public class RangedAlertMixin {

    @Inject(method = "releaseUsingItem", at = @At("HEAD"))
    private void lattedoom$alertOnRelease(Player player, CallbackInfo ci) {
        final ItemStack held = player.getUseItem();
        if (held.getItem() instanceof BowItem
            || held.getItem() instanceof CrossbowItem
            || held.getItem() instanceof TridentItem) {
            MinecraftCombat.alertMonsters();
        }
    }
}
