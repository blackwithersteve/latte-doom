package com.blackwithersteve.lattedoom.harness;

import com.blackwithersteve.lattedoom.LatteDoomConfig;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/**
 * Prints the seamless loader's classification for every WAD and patch file in a folder
 * (and its pwads/ subfolder when present): base game or patch, map scheme, map count,
 * foreign game, embedded DEHACKED. What /load decides from is exactly what this prints.
 *
 * Run: gradlew wadProbe -Pdir=/path/to/config/latte-doom
 */
public final class WadScanProbe {

    public static void main(String[] args) throws Exception {
        final Path root = Path.of(args[0]);
        for (final Path dir : new Path[]{root, root.resolve("pwads")}) {
            if (!Files.isDirectory(dir)) {
                continue;
            }
            System.out.println("== " + dir);
            try (Stream<Path> s = Files.list(dir)) {
                for (final Path p : s.sorted().toList()) {
                    final String n = p.getFileName().toString();
                    final String l = n.toLowerCase(java.util.Locale.ROOT);
                    if (l.endsWith(".deh") || l.endsWith(".bex")) {
                        System.out.printf("  %-22s DEH patch file%n", n);
                        continue;
                    }
                    if (!l.endsWith(".wad")) {
                        continue;
                    }
                    final String foreign = LatteDoomConfig.foreignGame(p);
                    if (foreign != null) {
                        System.out.printf("  %-22s FOREIGN (%s)%n", n, foreign);
                        continue;
                    }
                    final boolean base = LatteDoomConfig.isIwadFile(p);
                    final char fam = LatteDoomConfig.mapScheme(p);
                    final int maps = LatteDoomConfig.mapCount(p);
                    final boolean deh = LatteDoomConfig.hasDehackedLump(p);
                    System.out.printf("  %-22s %s  scheme=%c maps=%d%s%n", n,
                        base ? "GAME " : "PATCH", fam, maps,
                        deh ? "  embeds DEHACKED" : "");
                }
            }
        }
    }

    private WadScanProbe() {}
}
