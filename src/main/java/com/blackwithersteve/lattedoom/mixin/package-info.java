/**
 * Mixins that adapt Minecraft's client and server behaviour to DOOM's rules.
 *
 * <p>Three groups live here: rendering hooks that submit level geometry, sprites and the
 * status bar into Minecraft's own passes; movement and input hooks that hand the player's
 * motion to the DOOM collision and physics code; and rule hooks that suppress Minecraft
 * mechanics DOOM does not have, such as fall damage, hunger and death messages, for
 * players who are currently transformed.
 *
 * <p>Every mixin is conditional. A player who is neither transformed nor standing inside
 * a raised level observes unmodified vanilla behaviour.
 */
package com.blackwithersteve.lattedoom.mixin;
