package deh;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Latte Doom patch: DEHACKED/BEX parsed patch model.
 *
 * One instance per DEHACKED lump / .deh file. Purely passive data: the parser
 * (DehParser) fills it, the applier (DehLoader) walks it. Numbers use DEH
 * conventions: Thing sections are 1-based, Frame/Sound/Weapon/Ammo 0-based.
 */
public class DehPatch {

    /** section index -&gt; (field name -&gt; raw value string), insertion-ordered. */
    public final Map<Integer, Map<String, String>> things = new LinkedHashMap<>();
    public final Map<Integer, Map<String, String>> frames = new LinkedHashMap<>();
    public final Map<Integer, Map<String, String>> sounds = new LinkedHashMap<>();
    public final Map<Integer, Map<String, String>> weapons = new LinkedHashMap<>();
    public final Map<Integer, Map<String, String>> ammo = new LinkedHashMap<>();
    /** vanilla "Pointer N (Frame M)" sections: target frame M -&gt; source frame ("Codep Frame"). */
    public final Map<Integer, Integer> pointers = new LinkedHashMap<>();
    /** Misc section fields. */
    public final Map<String, String> misc = new LinkedHashMap<>();
    /** Cheat section fields (parsed, application intentionally skipped). */
    public final Map<String, String> cheats = new LinkedHashMap<>();
    /** BEX [CODEPTR]: frame -&gt; action name without the A_ prefix ("NULL" allowed). */
    public final Map<Integer, String> codeptr = new LinkedHashMap<>();
    /** BEX [STRINGS]: mnemonic -&gt; unescaped replacement. */
    public final Map<String, String> strings = new LinkedHashMap<>();
    /** BEX [PARS]: rows of {episode(-1 for MAPxx form), map, seconds}. */
    public final List<int[]> pars = new ArrayList<>();
    /** old-style Text sections: {original, replacement}. */
    public final List<String[]> texts = new ArrayList<>();

    /** parse diagnostics (kept short; logged capped). */
    public final List<String> problems = new ArrayList<>();
    public int unknownSections = 0;

    void problem(String s) {
        if (problems.size() < 40) {
            problems.add(s);
        }
    }

    public boolean isEmpty() {
        return things.isEmpty() && frames.isEmpty() && sounds.isEmpty() && weapons.isEmpty()
            && ammo.isEmpty() && pointers.isEmpty() && misc.isEmpty() && codeptr.isEmpty()
            && strings.isEmpty() && pars.isEmpty() && texts.isEmpty();
    }
}
