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
 * {@code give <item-id> <count>}, {@code loot <1-5>}.
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
        #   loot <1-5>          a random reward from that tier, for weapons
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

        # Weapons. An untransformed player cannot use a DOOM weapon, so these convert to
        # supplies instead. "loot <1-5>" draws a random reward from that tier.
        CSAW = loot 1
        SHOT = loot 1
        SGN2 = loot 2
        MGUN = loot 2
        LAUN = loot 3
        PLAS = loot 4
        BFUG = loot 5

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

    private static final java.util.Random LOOT = new java.util.Random();

    /** A tiered reward for a DOOM weapon. A weapon is of no use to an untransformed
     * player, so it converts to Minecraft supplies instead, and the better the weapon the
     * better the roll. Every call draws independently, so one tier covers several
     * different rewards. */
    private static Action rollLoot(int tier) {
        final java.util.Random r = LOOT;
        final int roll = r.nextInt(3);
        return switch (tier) {
            case 1 -> switch (roll) { // chainsaw, shotgun
                case 0 -> new Action(0, 0, "minecraft:arrow", 8 + r.nextInt(9));
                case 1 -> new Action(0, 6, "", 0);
                default -> new Action(0, 0, "minecraft:iron_ingot", 1 + r.nextInt(2));
            };
            case 2 -> switch (roll) { // super shotgun, chaingun
                case 0 -> new Action(0, 0, "minecraft:arrow", 16 + r.nextInt(17));
                case 1 -> new Action(0, 0, "minecraft:iron_ingot", 2 + r.nextInt(3));
                default -> new Action(0, 0, "minecraft:golden_apple", 1);
            };
            case 3 -> switch (roll) { // rocket launcher
                case 0 -> new Action(0, 0, "minecraft:iron_ingot", 4 + r.nextInt(4));
                case 1 -> new Action(0, 0, "minecraft:tnt", 2 + r.nextInt(3));
                default -> new Action(0, 0, "minecraft:diamond", 1);
            };
            case 4 -> switch (roll) { // plasma rifle
                case 0 -> new Action(0, 0, "minecraft:diamond", 1 + r.nextInt(2));
                case 1 -> new Action(0, 0, "minecraft:experience_bottle", 4 + r.nextInt(5));
                default -> new Action(0, 0, "minecraft:golden_apple", 2);
            };
            default -> switch (roll) { // BFG
                case 0 -> new Action(0, 0, "minecraft:diamond", 3 + r.nextInt(3));
                case 1 -> new Action(0, 0, "minecraft:netherite_scrap", 1);
                default -> new Action(0, 0, "minecraft:enchanted_golden_apple", 1);
            };
        };
    }

    private static Action parse(String value) {
        final String[] p = value.trim().split("\\s+");
        try {
            return switch (p[0].toLowerCase(java.util.Locale.ROOT)) {
                case "heal" -> new Action(Integer.parseInt(p[1]), 0, "", 0);
                case "food" -> new Action(0, Integer.parseInt(p[1]), "", 0);
                case "give" -> new Action(0, 0, p[1], p.length > 2 ? Integer.parseInt(p[2]) : 1);
                case "loot" -> rollLoot(Math.max(1, Math.min(5, Integer.parseInt(p[1]))));
                default -> null; // "none" and anything unrecognised: not convertible
            };
        } catch (RuntimeException bad) {
            return null; // malformed line: skipped, so the item stays on the floor
        }
    }

    /** The conversion for a sprite name, or null when that item is not convertible. */
    public static Action action(String sprite) {
        final Action a = table.get(sprite);
        // An entry whose item id is the loot sentinel carries its tier in the count and is
        // drawn here, at the moment of the pickup, instead of reusing one fixed reward.
        return a != null && LOOT_TIER.equals(a.item()) ? rollLoot(a.count()) : a;
    }

    /** Sentinel item id marking a parsed action as a loot tier to roll at pickup rather
     * than a literal item. The leading NUL cannot occur in a real item id. */
    private static final String LOOT_TIER = "\0loot";

    /** Immutable snapshot of the sprite names the engine may consume on contact. */
    public static Set<String> consumableSprites() {
        return sprites;
    }

    private PickupConfig() {}
}
