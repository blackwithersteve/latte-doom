package com.blackwithersteve.lattedoom.harness;

import com.blackwithersteve.lattedoom.render.WadFile;

import java.nio.file.Path;

/**
 * WHAT COLOUR IS THE ART? Decides whether a Minecraft blit tint can recolour a given WAD
 * font, because a tint is a MULTIPLY: it can darken a channel but never add one. White art
 * takes any colour; red art can only ever become darker red.
 *
 * The menu draws its rows in STCFN (DOOM's small HUD font) and the status bar draws its
 * numbers in STTNUM. Whether either can be recoloured by tinting decides whether a
 * per-pixel recolour pipeline is needed at all, and for which of them.
 *
 * Run: gradlew fontProbe -Pwad=&lt;absolute path to an IWAD&gt;
 */
public final class FontProbe {

    public static void main(String[] args) throws Exception {
        final WadFile wad = WadFile.read(Path.of(args[0]));
        final byte[] pal = wad.lumpBytes("PLAYPAL");
        if (pal == null) {
            System.out.println("RESULT: FAIL, no PLAYPAL");
            System.exit(2);
        }
        for (String lump : new String[]{"STCFN065", "STCFN066", "STTNUM0", "STTNUM1",
            "STYSNUM0", "STTPRCNT"}) {
            report(lump, wad.lumpBytes(lump), pal);
        }
        System.exit(0);
    }

    private static void report(String name, byte[] data, byte[] pal) {
        if (data == null || data.length < 8) {
            System.out.printf("%-9s absent%n", name);
            return;
        }
        final int w = u16(data, 0), h = u16(data, 2);
        long r = 0, g = 0, b = 0, n = 0, maxR = 0, maxG = 0, maxB = 0;
        for (int c = 0; c < w; c++) {
            int ofs = (int) u32(data, 8 + c * 4);
            while (ofs + 1 < data.length) {
                final int top = data[ofs] & 0xFF;
                if (top == 0xFF) {
                    break;
                }
                final int len = data[ofs + 1] & 0xFF;
                for (int i = 0; i < len && ofs + 3 + i < data.length; i++) {
                    final int idx = (data[ofs + 3 + i] & 0xFF) * 3;
                    final int pr = pal[idx] & 0xFF, pg = pal[idx + 1] & 0xFF,
                        pb = pal[idx + 2] & 0xFF;
                    r += pr;
                    g += pg;
                    b += pb;
                    maxR = Math.max(maxR, pr);
                    maxG = Math.max(maxG, pg);
                    maxB = Math.max(maxB, pb);
                    n++;
                }
                ofs += len + 4;
            }
        }
        if (n == 0) {
            System.out.printf("%-9s %dx%d, no pixels%n", name, w, h);
            return;
        }
        // a tint can only scale a channel down, so the CEILING of each channel is what
        // limits which colours the art can be turned into
        final String verdict = (maxG >= 128 && maxB >= 128 && maxR >= 128)
            ? "TINTABLE to any colour"
            : "NOT tintable — needs a per-pixel recolour";
        System.out.printf("%-9s %2dx%-2d  mean rgb(%3d,%3d,%3d)  ceiling rgb(%3d,%3d,%3d)  %s%n",
            name, w, h, r / n, g / n, b / n, maxR, maxG, maxB, verdict);
    }

    private static int u16(byte[] d, int i) {
        return (d[i] & 0xFF) | ((d[i + 1] & 0xFF) << 8);
    }

    private static long u32(byte[] d, int i) {
        return (d[i] & 0xFFL) | ((d[i + 1] & 0xFFL) << 8)
            | ((d[i + 2] & 0xFFL) << 16) | ((d[i + 3] & 0xFFL) << 24);
    }

    private FontProbe() {
    }
}
