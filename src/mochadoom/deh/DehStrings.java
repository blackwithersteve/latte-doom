package deh;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Latte Doom patch: DEHACKED/BEX string overrides.
 *
 * Runtime side of BEX [STRINGS]. Most of mocha's text lives in englsh.java as
 * static final String constants, which javac inlines at every use site, so those can
 * never be patched at runtime. What is reachable is pushed into live tables by
 * DehLoader (HU level-name arrays, Finale text arrays); the rest lands here so that
 * marked hook sites (cast-call names, and any future ones) can consult it lazily.
 */
public final class DehStrings {

    private DehStrings() {
    }

    private static final Map<String, String> overrides = new LinkedHashMap<>();

    /** Cast-call name mnemonics in Finale castorder order. */
    public static final String[] CC_MNEMONICS = {
        "CC_ZOMBIE", "CC_SHOTGUN", "CC_HEAVY", "CC_IMP", "CC_DEMON", "CC_LOST", "CC_CACO",
        "CC_HELL", "CC_BARON", "CC_ARACH", "CC_PAIN", "CC_REVEN", "CC_MANCU", "CC_ARCH",
        "CC_SPIDER", "CC_CYBER", "CC_HERO"
    };

    public static void put(String mnemonic, String value) {
        overrides.put(mnemonic, value);
    }

    /** The override for a BEX mnemonic, or the given default. */
    public static String override(String mnemonic, String def) {
        final String v = overrides.get(mnemonic);
        return v != null ? v : def;
    }

    /** Cast-call name for castorder slot i (marked hook in Finale). */
    public static String castName(int i, String def) {
        if (i >= 0 && i < CC_MNEMONICS.length) {
            return override(CC_MNEMONICS[i], def);
        }
        return def;
    }

    public static int count() {
        return overrides.size();
    }
}
