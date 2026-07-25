package com.blackwithersteve.lattedoom.render;

import net.minecraft.client.Minecraft;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.Clip;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.FloatControl;
import javax.sound.sampled.LineEvent;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Plays a sound event: an effect id plus a map position, from the local player's own
 * WAD. This is how a spectator hears the world of the client running the engine: the
 * network carries ids and coordinates only, per rule 6 of {@code LEGAL.md}, and the
 * {@code DS*} lump bytes are read from this client's disk.
 *
 * <p>Volume follows the engine's own distance attenuation, full inside 200 map units and
 * silent beyond 1200, scaled by the mod's dedicated sound-effect volume. Playback uses
 * Java Sound, the same output path the engine uses natively.
 */
public final class DoomSfx {

    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor(r -> {
        final Thread t = new Thread(r, "LatteDoom-RemoteSfx");
        t.setDaemon(true);
        return t;
    });
    private static final AtomicInteger LIVE = new AtomicInteger();
    private static final int MAX_LIVE = 10;

    /** Plays one sound event relative to the local listener, applying the engine's
     * distance attenuation and its ±96/128 stereo swing about the listener's facing.
     * With {@code hasPos} false the sound is world-global: full volume, centred. */
    public static void play(int sfxId, boolean hasPos, double doomX, double doomY) {
        play(sfxId, hasPos, doomX, doomY, 1.0);
    }

    /** As above, with an additional gain applied under the volume setting. Sounds played
     * outside the engine's own mixer need it: the death scream uses 0.4 to sit at the
     * same loudness as engine-mixed gameplay sounds. */
    public static void play(int sfxId, boolean hasPos, double doomX, double doomY, double gain) {
        final Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || sfxId <= 0 || sfxId >= data.sounds.S_sfx.length) {
            return;
        }
        double vol = com.blackwithersteve.lattedoom.LatteDoomClient.doomSfxVolume() * gain;
        double pan = 0;
        if (hasPos) {
            final double lx = LatteWorld.worldToDoomX(mc.player.getX());
            final double ly = LatteWorld.worldToDoomY(mc.player.getZ());
            final double dx = doomX - lx, dy = doomY - ly;
            final double dist = Math.hypot(dx, dy);
            if (dist > 200.0) {
                vol *= Math.max(0.0, (1200.0 - dist) / 1000.0);
            }
            if (dist > 16.0) {
                // S_AdjustSoundParams: pan by the angle between the listener's facing and
                // the source, with the original's 96/128 swing so no channel goes silent.
                final double a = Math.toRadians(-mc.player.getYRot() - 90.0);
                final double rightness = (dx / dist) * Math.sin(a) - (dy / dist) * Math.cos(a);
                pan = 0.75 * rightness;
            }
        }
        if (vol < 0.02 || LIVE.get() >= MAX_LIVE) {
            return;
        }
        final String name = data.sounds.S_sfx[sfxId].name;
        if (name == null) {
            return;
        }
        final byte[] lump = LatteWorld.wadLump("DS" + name.toUpperCase(java.util.Locale.ROOT));
        if (lump == null || lump.length <= 8) {
            return;
        }
        playLump(lump, vol, pan);
    }

    /** Shared DMX→stereo playback: constant-power pan baked into the samples. */
    private static void playLump(byte[] lump, double vol, double pan) {
        final double fvol = vol;
        final double fpan = Math.max(-1.0, Math.min(1.0, pan));
        LIVE.incrementAndGet();
        EXEC.submit(() -> {
            try {
                // DMX format: u16 type, u16 sample rate, u32 length, then unsigned 8-bit PCM
                final int rate = (lump[2] & 0xFF) | ((lump[3] & 0xFF) << 8);
                final int len = Math.min(lump.length - 8,
                    (lump[4] & 0xFF) | ((lump[5] & 0xFF) << 8) | ((lump[6] & 0xFF) << 16));
                final double lobe = (fpan + 1.0) * Math.PI / 4.0;
                final double lGain = fvol * Math.cos(lobe);
                final double rGain = fvol * Math.sin(lobe);
                final byte[] stereo = new byte[len * 2];
                for (int i = 0; i < len; i++) {
                    final int centered = (lump[8 + i] & 0xFF) - 128;
                    stereo[i * 2] = (byte) (128 + (int) (centered * lGain));
                    stereo[i * 2 + 1] = (byte) (128 + (int) (centered * rGain));
                }
                final AudioFormat fmt = new AudioFormat(AudioFormat.Encoding.PCM_UNSIGNED,
                    rate, 8, 2, 2, rate, false);
                final Clip clip = AudioSystem.getClip();
                clip.open(fmt, stereo, 0, stereo.length);
                clip.addLineListener(ev -> {
                    if (ev.getType() == LineEvent.Type.STOP) {
                        clip.close();
                        LIVE.decrementAndGet();
                    }
                });
                clip.start();
            } catch (Throwable t) {
                LIVE.decrementAndGet();
            }
        });
    }

    private DoomSfx() {}
}
