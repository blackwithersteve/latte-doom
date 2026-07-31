package com.blackwithersteve.lattedoom.harness;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import data.info;
import deh.DehLoader;
import deh.DehParser;
import deh.DehPatch;
import deh.DehState;

/**
 * Standalone DEHACKED/BEX parser+applier proof (no engine boot, no wad init).
 *
 * Feed it a .deh/.bex text file or a .wad (every DEHACKED lump inside is used, in
 * order). It parses, applies against the real static tables (things/frames/codeptr/
 * sprites — the game-instance homes are skipped), and prints the counts. Exits 0 and
 * prints "DEH PROBE OK" when parsing produced zero problems.
 *
 * Default target: Eviternity's DEHACKED, the M-BOOM slice 7 acceptance wad.
 */
public final class DehProbe {

    private static final String DEFAULT_WAD =
        "Eviternity.wad";

    public static void main(String[] args) throws IOException {
        final Path path = Path.of(args.length > 0 ? args[0] : DEFAULT_WAD);
        if (!Files.exists(path)) {
            System.err.println("DehProbe: no such file: " + path);
            System.exit(2);
        }

        final List<String> lumps = new ArrayList<>();
        final String name = path.getFileName().toString().toLowerCase();
        if (name.endsWith(".wad")) {
            lumps.addAll(extractDehLumps(Files.readAllBytes(path)));
            System.out.println("DehProbe: " + lumps.size() + " DEHACKED lump(s) in " + path.getFileName());
        } else {
            lumps.add(new String(Files.readAllBytes(path), StandardCharsets.ISO_8859_1));
        }
        if (lumps.isEmpty()) {
            System.err.println("DehProbe: no DEHACKED content found");
            System.exit(2);
        }

        int totalProblems = 0;
        for (int i = 0; i < lumps.size(); i++) {
            final String text = lumps.get(i);
            final DehPatch p = DehParser.parse(text);
            System.out.println("--- lump " + (i + 1) + ": " + text.length() + " chars ---");
            System.out.println("parsed: " + p.things.size() + " things, " + p.frames.size() + " frames, "
                + p.pointers.size() + " pointers, " + p.codeptr.size() + " codeptrs, "
                + p.sounds.size() + " sounds, " + p.ammo.size() + " ammo, " + p.weapons.size() + " weapons, "
                + p.misc.size() + " misc fields, " + p.texts.size() + " texts, "
                + p.strings.size() + " strings, " + p.pars.size() + " pars, "
                + p.cheats.size() + " cheat fields, " + p.unknownSections + " unknown sections");
            for (final String problem : p.problems) {
                System.out.println("  PROBLEM: " + problem);
            }
            totalProblems += p.problems.size();

            // real application against the static tables (DOOM == null: HU/pars homes skipped)
            DehLoader.applyText(text, null);
        }

        System.out.println("tables now: states=" + info.states.length
            + " mobjinfo=" + info.mobjinfo.length
            + " sprnames=" + DehState.sprnames().length
            + " dehActive=" + DehState.active);

        // spot checks: a patched extension state and the MBF thing slots, if present
        if (info.states.length > 1073) {
            final data.state_t st = info.states[1073];
            System.out.println("sample state 1073: sprite=" + st.dehSpriteNum() + " ("
                + DehState.spriteNameOf(st.dehSpriteNum()) + ") frame=" + st.frame
                + " tics=" + st.tics + " next=" + st.dehNextState() + " action=" + st.action);
        }
        if (info.mobjinfo.length > 143) {
            System.out.println("sample mobjinfo 139 (MBF dog slot): ednum=" + info.mobjinfo[139].doomednum
                + " hp=" + info.mobjinfo[139].spawnhealth + " speed=" + info.mobjinfo[139].speed
                + " seestate=" + info.mobjinfo[139].seeStateNum());
            System.out.println("sample mobjinfo 143 (MBF bible slot): ednum=" + info.mobjinfo[143].doomednum
                + " hp=" + info.mobjinfo[143].spawnhealth + " deathstate=" + info.mobjinfo[143].deathStateNum());
        }

        if (totalProblems == 0) {
            System.out.println("DEH PROBE OK");
            System.exit(0);
        } else {
            System.out.println("DEH PROBE FAILED: " + totalProblems + " parse problems");
            System.exit(1);
        }
    }

    /** Minimal read-only WAD directory walk; returns DEHACKED lump texts in order. */
    private static List<String> extractDehLumps(byte[] wad) {
        final List<String> out = new ArrayList<>();
        final ByteBuffer buf = ByteBuffer.wrap(wad).order(ByteOrder.LITTLE_ENDIAN);
        buf.position(4); // IWAD/PWAD
        final int numLumps = buf.getInt();
        final int dirOfs = buf.getInt();
        for (int i = 0; i < numLumps; i++) {
            final int base = dirOfs + 16 * i;
            if (base + 16 > wad.length) {
                break;
            }
            buf.position(base);
            final int filepos = buf.getInt();
            final int size = buf.getInt();
            final byte[] nameBytes = new byte[8];
            buf.get(nameBytes);
            int len = 0;
            while (len < 8 && nameBytes[len] != 0) {
                len++;
            }
            final String lumpName = new String(nameBytes, 0, len, StandardCharsets.US_ASCII);
            if ("DEHACKED".equalsIgnoreCase(lumpName) && filepos >= 0 && filepos + size <= wad.length) {
                out.add(new String(wad, filepos, size, StandardCharsets.ISO_8859_1));
            }
        }
        return out;
    }

    private DehProbe() {
    }
}
