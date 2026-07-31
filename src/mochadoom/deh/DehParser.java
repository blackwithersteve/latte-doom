package deh;

import java.util.Locale;
import java.util.Map;

/**
 * Latte Doom additive patch — DEHACKED/BEX parser (M-BOOM slice 7).
 *
 * Text-format DEHACKED (v2.3+ text patches, "Patch format = 5/6") plus the BEX
 * extensions Boom/MBF wads ship ([CODEPTR], [STRINGS], [PARS]). Binary .deh
 * patches (format &lt; 5) are not supported (none ship inside wads). Defensive
 * throughout: unknown sections and fields are recorded, never fatal.
 */
public final class DehParser {

    private final String text;
    private int pos;
    private final DehPatch patch = new DehPatch();

    private DehParser(String text) {
        this.text = text;
    }

    public static DehPatch parse(String text) {
        return new DehParser(text).run();
    }

    // ------------------------------------------------------------------ scan
    private boolean eof() {
        return pos >= text.length();
    }

    /** Reads one line (without the terminator). */
    private String readLine() {
        int i = pos;
        final int n = text.length();
        while (i < n && text.charAt(i) != '\n' && text.charAt(i) != '\r') {
            i++;
        }
        final String line = text.substring(pos, i);
        if (i < n && text.charAt(i) == '\r') {
            i++;
        }
        if (i < n && text.charAt(i) == '\n') {
            i++;
        }
        pos = i;
        return line;
    }

    /**
     * Consumes exactly n raw chars (old-style Text blocks), newline-inclusive.
     * readLine() already consumed the header's terminator, so pos sits on the payload.
     */
    private String readRaw(int n) {
        final int end = Math.min(text.length(), pos + n);
        final String s = text.substring(pos, end);
        pos = end;
        return s;
    }

    // ------------------------------------------------------------------ run
    private DehPatch run() {
        String pendingLine = null;
        while (pendingLine != null || !eof()) {
            final String raw = pendingLine != null ? pendingLine : readLine();
            pendingLine = null;
            final String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            final String lower = line.toLowerCase(Locale.ROOT);
            try {
                if (lower.startsWith("patch file for dehacked")
                    || lower.startsWith("doom version") || lower.startsWith("patch format")) {
                    continue; // header chatter
                } else if (lower.equals("[codeptr]")) {
                    pendingLine = bexCodeptr();
                } else if (lower.equals("[strings]")) {
                    pendingLine = bexStrings();
                } else if (lower.equals("[pars]")) {
                    pendingLine = bexPars();
                } else if (lower.startsWith("[")) {
                    // [SPRITES]/[SOUNDS]/[MUSIC]/[HELPER]... — recognized BEX blocks we skip whole
                    patch.unknownSections++;
                    patch.problem("unsupported BEX block " + line + " (skipped)");
                    pendingLine = skipBlock();
                } else if (lower.startsWith("include")) {
                    patch.problem("BEX Include not supported inside lumps (skipped): " + line);
                } else if (lower.startsWith("thing ")) {
                    pendingLine = fields(patch.things, headerNumber(line, 1));
                } else if (lower.startsWith("frame ")) {
                    pendingLine = fields(patch.frames, headerNumber(line, 1));
                } else if (lower.startsWith("sound ")) {
                    pendingLine = fields(patch.sounds, headerNumber(line, 1));
                } else if (lower.startsWith("weapon ")) {
                    pendingLine = fields(patch.weapons, headerNumber(line, 1));
                } else if (lower.startsWith("ammo ")) {
                    pendingLine = fields(patch.ammo, headerNumber(line, 1));
                } else if (lower.startsWith("pointer ")) {
                    pendingLine = pointer(line);
                } else if (lower.startsWith("cheat ")) {
                    pendingLine = fieldsInto(patch.cheats);
                } else if (lower.startsWith("misc ") || lower.equals("misc")) {
                    pendingLine = fieldsInto(patch.misc);
                } else if (lower.startsWith("text ")) {
                    textSection(line);
                } else {
                    patch.unknownSections++;
                    patch.problem("unknown section/line: " + abbrev(line));
                }
            } catch (Exception e) {
                patch.problem("parse error at '" + abbrev(line) + "': " + e);
            }
        }
        return patch;
    }

    private static String abbrev(String s) {
        return s.length() > 60 ? s.substring(0, 60) + "..." : s;
    }

    /** "Thing 25 (_Astral Cacodemon)" -&gt; 25. */
    private static int headerNumber(String line, int wordIndex) {
        final String[] w = line.split("\\s+");
        return Integer.parseInt(w[wordIndex]);
    }

    /**
     * A section header is "&lt;keyword&gt; &lt;number&gt; ..." (or a bracketed BEX block).
     * The number requirement matters: field lines like "Ammo type = 2" or [CODEPTR]
     * lines like "FRAME 1024 = FatAttack1" must NOT be mistaken for headers — the
     * latter is only a header when we are not inside [CODEPTR] (content is tried first).
     */
    private static final java.util.regex.Pattern SECTION_HEADER = java.util.regex.Pattern
        .compile("(?i)^(thing|frame|sound|weapon|ammo|pointer|cheat|misc|text)\\s+\\d.*");

    private static boolean isSectionStart(String line) {
        final String l = line.trim();
        if (l.startsWith("[")) {
            return true;
        }
        final String lower = l.toLowerCase(Locale.ROOT);
        if (lower.startsWith("include ") || lower.equals("misc")) {
            return true;
        }
        if (!SECTION_HEADER.matcher(l).matches()) {
            return false;
        }
        // "FRAME 1024 = FatAttack1" carries an '=' — that's [CODEPTR] content, not a header
        return l.indexOf('=') < 0;
    }

    // -------------------------------------------------------------- sections
    /** Key = value block. Returns the line that terminated the block (a new section header) or null. */
    private String fields(Map<Integer, Map<String, String>> into, int index) {
        final Map<String, String> map = into.computeIfAbsent(index, k -> new java.util.LinkedHashMap<>());
        return fieldsInto(map);
    }

    private String fieldsInto(Map<String, String> map) {
        while (!eof()) {
            final String raw = readLine();
            final String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue; // blank lines are tolerated; sections end at the next header
            }
            if (isSectionStart(line)) {
                return raw;
            }
            final int eq = line.indexOf('=');
            if (eq < 0) {
                patch.problem("field without '=': " + abbrev(line));
                continue;
            }
            final String key = line.substring(0, eq).trim();
            final String val = line.substring(eq + 1).trim();
            map.put(key, val);
        }
        return null;
    }

    /** "Pointer 21 (Frame 34)" + field "Codep Frame = N". */
    private String pointer(String header) {
        int target = -1;
        final java.util.regex.Matcher m = java.util.regex.Pattern
            .compile("(?i)pointer\\s+\\d+\\s*\\(\\s*frame\\s+(\\d+)\\s*\\)").matcher(header);
        if (m.find()) {
            target = Integer.parseInt(m.group(1));
        } else {
            patch.problem("Pointer header without (Frame N): " + abbrev(header));
        }
        final Map<String, String> map = new java.util.LinkedHashMap<>();
        final String next = fieldsInto(map);
        if (target >= 0) {
            final String cf = firstKeyIgnoreCase(map, "Codep Frame");
            if (cf != null) {
                try {
                    patch.pointers.put(target, Integer.parseInt(cf.trim()));
                } catch (NumberFormatException e) {
                    patch.problem("bad Codep Frame: " + cf);
                }
            }
        }
        return next;
    }

    private static String firstKeyIgnoreCase(Map<String, String> map, String key) {
        for (final Map.Entry<String, String> e : map.entrySet()) {
            if (e.getKey().equalsIgnoreCase(key)) {
                return e.getValue();
            }
        }
        return null;
    }

    /** old-style raw "Text a b" section. */
    private void textSection(String header) {
        final String[] w = header.trim().split("\\s+");
        if (w.length < 3) {
            patch.problem("bad Text header: " + abbrev(header));
            return;
        }
        final int a = Integer.parseInt(w[1]);
        final int b = Integer.parseInt(w[2]);
        if (a < 0 || b < 0 || a + b > 65536) {
            patch.problem("unreasonable Text sizes: " + a + " " + b);
            return;
        }
        final String block = readRaw(a + b);
        if (block.length() < a + b) {
            patch.problem("truncated Text block (" + block.length() + " < " + (a + b) + ")");
            return;
        }
        patch.texts.add(new String[] { block.substring(0, a), block.substring(a, a + b) });
    }

    private static final java.util.regex.Pattern CODEPTR_LINE = java.util.regex.Pattern
        .compile("(?i)frame\\s+(\\d+)\\s*=\\s*(\\S+)");

    /** BEX [CODEPTR]: FRAME n = Name. Returns terminating header line or null. */
    private String bexCodeptr() {
        while (!eof()) {
            final String raw = readLine();
            final String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            // content first: "FRAME n = Name" would otherwise look like a Frame header
            final java.util.regex.Matcher m = CODEPTR_LINE.matcher(line);
            if (m.matches()) {
                patch.codeptr.put(Integer.parseInt(m.group(1)), m.group(2));
                continue;
            }
            if (isSectionStart(line)) {
                return raw;
            }
            patch.problem("bad [CODEPTR] line: " + abbrev(line));
        }
        return null;
    }

    /** BEX [STRINGS]: KEY = value, with trailing-backslash continuation and \n escapes. */
    private String bexStrings() {
        while (!eof()) {
            final String raw = readLine();
            final String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            if (isSectionStart(line)) {
                return raw;
            }
            final int eq = line.indexOf('=');
            if (eq < 0) {
                patch.problem("bad [STRINGS] line: " + abbrev(line));
                continue;
            }
            final String key = line.substring(0, eq).trim();
            StringBuilder val = new StringBuilder(line.substring(eq + 1).trim());
            // continuation: a trailing backslash joins the next line
            while (val.length() > 0 && val.charAt(val.length() - 1) == '\\' && !eof()) {
                val.setLength(val.length() - 1);
                val.append(readLine().trim());
            }
            patch.strings.put(key, unescape(val.toString()));
        }
        return null;
    }

    static String unescape(String s) {
        final StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            final char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                final char n = s.charAt(++i);
                switch (n) {
                    case 'n': out.append('\n'); break;
                    case 't': out.append('\t'); break;
                    case 'r': out.append('\r'); break;
                    case '"': out.append('"'); break;
                    case '\\': out.append('\\'); break;
                    default: out.append('\\').append(n); break; // keep unknown escapes literal
                }
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    /** BEX [PARS]: "par map time" or "par episode map time". */
    private String bexPars() {
        while (!eof()) {
            final String raw = readLine();
            final String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            if (isSectionStart(line)) {
                return raw;
            }
            final String[] w = line.split("\\s+");
            try {
                if (w.length == 3 && w[0].equalsIgnoreCase("par")) {
                    patch.pars.add(new int[] { -1, Integer.parseInt(w[1]), Integer.parseInt(w[2]) });
                } else if (w.length == 4 && w[0].equalsIgnoreCase("par")) {
                    patch.pars.add(new int[] { Integer.parseInt(w[1]), Integer.parseInt(w[2]), Integer.parseInt(w[3]) });
                } else {
                    patch.problem("bad [PARS] line: " + abbrev(line));
                }
            } catch (NumberFormatException e) {
                patch.problem("bad [PARS] number: " + abbrev(line));
            }
        }
        return null;
    }

    /** Skips a bracketed BEX block we do not support. */
    private String skipBlock() {
        while (!eof()) {
            final String raw = readLine();
            final String line = raw.trim();
            if (isSectionStart(line)) {
                return raw;
            }
        }
        return null;
    }
}
