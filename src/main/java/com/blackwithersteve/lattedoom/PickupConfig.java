package com.blackwithersteve.lattedoom;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * What an untransformed player gets for picking up a DOOM item, from
 * {@code config/latte-doom/pickups.properties}. Keyed by four-character sprite name, so the
 * table still works for items a DEHACKED patch has modified. Unlisted sprites, and those
 * mapped to {@code none}, are left on the floor. Actions: {@code heal}, {@code food},
 * {@code give <item-id> <count>}.
 */
public final class PickupConfig {

    /** One conversion, resolved ahead of the network call: any field may be zero or empty. */
    public record Action(int heal, int food, String item, int count) {}

    private static final String FILE = "pickups.properties";

    private static final String DEFAULTS = """
        # Latte Doom - item conversion table.
        #
        # What an untransformed Minecraft player receives for walking over a DOOM item.
        # The item is consumed for everyone. Anything not listed here, or set to "none",
        # is left on the floor for a transformed player to collect normally.
        #
        # The key is the item's four-letter sprite name. Actions:
        #   heal <points>       Minecraft health points (2 = one heart)
        #   food <points>       Minecraft hunger points (2 = one drumstick)
        #   give <item-id> <n>  any Minecraft item, e.g. give minecraft:arrow 4
        #   none                not collectable by an untransformed player
        #
        # Common sprites: STIM stimpack, MEDI medikit, BON1 health bonus,
        # BON2 armor bonus, ARM1 green armor, ARM2 blue armor, CLIP clip,
        # AMMO bullet box, SHEL shells, SBOX shell box, ROCK rocket, BROK rocket box,
        # CELL cell, CELP cell pack, BPAK backpack, SOUL soulsphere.

        STIM = heal 4
        MEDI = heal 10
        BON1 = heal 1
        ARM1 = food 6
        ARM2 = food 10
        BON2 = food 1
        CLIP = give minecraft:arrow 4
        AMMO = give minecraft:arrow 16
        SHEL = give minecraft:arrow 4
        SBOX = give minecraft:arrow 16
        BPAK = give minecraft:arrow 24

        # Reserved for transformed players by default; uncomment to convert them too.
        # SOUL = heal 40
        # CELL = give minecraft:experience_bottle 1
        # ROCK = give minecraft:tnt 1
        """;

    private static volatile Map<String, Action> table = Map.of();
    private static volatile Set<String> sprites = Set.of();

    /** Reloads the table, writing the annotated default file on first run. */
    public static synchronized void reload(Path configDir) {
        try {
            final Path file = configDir.resolve(FILE);
            if (!Files.exists(file)) {
                Files.createDirectories(configDir);
                Files.writeString(file, DEFAULTS, StandardCharsets.UTF_8);
            }
            final Properties props = new Properties();
            try (InputStream in = Files.newInputStream(file)) {
                props.load(in);
            }
            final Map<String, Action> t = new HashMap<>();
            for (String key : props.stringPropertyNames()) {
                final Action a = parse(props.getProperty(key, ""));
                if (a != null) {
                    t.put(key.trim().toUpperCase(java.util.Locale.ROOT), a);
                }
            }
            table = Map.copyOf(t);
            sprites = Set.copyOf(t.keySet());
        } catch (IOException e) {
            System.err.println("[lattedoom] pickups config unreadable: " + e);
        }
    }

    private static Action parse(String value) {
        final String[] p = value.trim().split("\\s+");
        try {
            return switch (p[0].toLowerCase(java.util.Locale.ROOT)) {
                case "heal" -> new Action(Integer.parseInt(p[1]), 0, "", 0);
                case "food" -> new Action(0, Integer.parseInt(p[1]), "", 0);
                case "give" -> new Action(0, 0, p[1], p.length > 2 ? Integer.parseInt(p[2]) : 1);
                default -> null; // "none" and anything unrecognised: not convertible
            };
        } catch (RuntimeException bad) {
            return null; // malformed line: skipped, so the item stays on the floor
        }
    }

    /** The conversion for a sprite name, or null when that item is not convertible. */
    public static Action action(String sprite) {
        return table.get(sprite);
    }

    /** Immutable snapshot of the sprite names the engine may consume on contact. */
    public static Set<String> consumableSprites() {
        return sprites;
    }

    private PickupConfig() {}
}
