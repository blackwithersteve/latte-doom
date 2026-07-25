package com.blackwithersteve.lattedoom.mixin;

import com.blackwithersteve.lattedoom.play.MarineRoster;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.gamerules.GameRule;
import net.minecraft.world.level.gamerules.GameRules;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Death inside a level follows DOOM's rules rather than Minecraft's: there is no death
 * message and no scattered inventory, only the level restart described in
 * {@code docs/ARCHITECTURE.md}. {@code ServerPlayer.die} consults exactly two game rules,
 * which are answered locally for players on the marine roster while the world's own
 * settings stay untouched for everyone else:
 * <ul>
 *   <li>{@code SHOW_DEATH_MESSAGES} returns false, so no chat broadcast or death-screen
 *       text competes with the DOOM death sequence.</li>
 *   <li>{@code KEEP_INVENTORY} returns true, so a player's Minecraft items are not
 *       dropped into the level's void dimension; the DOOM arsenal is engine state and
 *       unaffected either way.</li>
 * </ul>
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
