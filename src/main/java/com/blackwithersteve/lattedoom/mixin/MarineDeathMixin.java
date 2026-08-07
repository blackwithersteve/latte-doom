package com.blackwithersteve.lattedoom.mixin;

import com.blackwithersteve.lattedoom.play.MarineRoster;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.gamerules.GameRule;
import net.minecraft.world.level.gamerules.GameRules;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * A DOOM death belongs to the engine, not to Minecraft. ServerPlayer.die consults exactly
 * two game rules; for players on the marine roster they are answered the DOOM way, with
 * world settings left untouched for everyone else:
 *  - SHOW_DEATH_MESSAGES -&gt; false: no chat broadcast and no death-screen text, because
 *    the death is handled by the DOOM flow (the map restarts, reborn at the start).
 *  - KEEP_INVENTORY -> true: the marine's Minecraft belongings must NOT scatter into the
 *    level's void dimension (the arsenal is suit state, the rest is his overworld kit).
 */
@Mixin(ServerPlayer.class)
public abstract class MarineDeathMixin {

    @Redirect(method = "die",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/gamerules/GameRules;get(Lnet/minecraft/world/level/gamerules/GameRule;)Ljava/lang/Object;"))
    private Object lattedoom$doomDeathRules(GameRules rules, GameRule<?> rule) {
        final ServerPlayer self = (ServerPlayer) (Object) this;
        if (MarineRoster.SERVER.contains(self.getUUID())) {
            if (rule == GameRules.SHOW_DEATH_MESSAGES) {
                return Boolean.FALSE;
            }
            if (rule == GameRules.KEEP_INVENTORY) {
                return Boolean.TRUE;
            }
        }
        return rules.get(rule);
    }
}
