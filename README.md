# Latte Doom

A DOOM source port for Minecraft. The vendored Mocha Doom engine runs inside Minecraft's
JVM and owns all simulation; Minecraft renders it.

Requires Minecraft 26.2 with Fabric and Fabric API, Java 25, and your own copy of DOOM.

## Game data

No game data ships with this mod. Install the jar, start the game once, then place an IWAD
you own in:

```
config/latte-doom/
```

DOOM, DOOM II, Ultimate DOOM, Final DOOM and Freedoom are recognised. Patch WADs and
DEHACKED files can go in the same folder or in `config/latte-doom/pwads/`, and are loaded
from the in-game menu. Press **M** for the menu.

With no WAD present the mod stays idle and says what is missing. It never downloads or
bundles game data.

## Building

Requires JDK 25.

```
./gradlew build -x test
```

The jar is written to `build/libs/`.

### Verification

The build carries headless tasks that boot the real engine without Minecraft. Each needs
an IWAD, except `sessionProbe`, which asserts pure state:

```
./gradlew doomSmoke        -Pwad=<path to an IWAD>
./gradlew transitionProbe  -Pwad=<path to an IWAD>
./gradlew saveProbe        -Pwad=<path to an IWAD>
./gradlew messageProbe     -Pwad=<path to an IWAD>
./gradlew sessionProbe
```

`./gradlew tasks --group=verification` lists the rest.

## Licence

GPL-3.0-or-later, inherited from the vendored engine. See [LICENSE](LICENSE).

## Legal

This mod contains no id Software assets. Sprites, sounds, textures, maps and fonts are read
at runtime from the IWAD on your own disk, which is how a source port works. Nothing is
redistributed. See [LEGAL.md](LEGAL.md).

## Credits

The engine is [Mocha Doom](https://github.com/AXDOOMER/mochadoom), a Java port of the DOOM
source released by id Software, itself following Chocolate Doom.

The menu and HUD follow GZDoom and UZDoom in structure, layout and scaling behaviour. The
crispness options follow Crispy Doom. Boom and MBF compatibility work follows Woof and
Nugget Doom. Menu geometry follows id's own `m_menu.c`.
