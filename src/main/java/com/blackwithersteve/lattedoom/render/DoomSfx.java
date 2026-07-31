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
 * Plays a DOOM sound EVENT (sfx id + map position) from the local player's OWN WAD —
 * how a spectator hears the level owner's world. The wire carries ids and coordinates
 * only (LEGAL.md rule 6); the DS* lump bytes come from this client's disk. Volume uses
 * DOOM's own attenuation (full inside 200u, silent past 1200u) times the dedicated DOOM
 * SFX slider. Playback is plain Java Sound, same as the engine's native output.
 */
public final class DoomSfx {

    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor(r -> {
        final Thread t = new Thread(r, "LatteDoom-RemoteSfx");
        t.setDaemon(true);
        return t;
    });
    private static final AtomicInteger LIVE = new AtomicInteger();
    private static final int MAX_LIVE = 10;

    /** Play one remote sound event, positioned in YOUR ears: DOOM's distance
     * attenuation and its ±96/128 stereo swing, panned against your own facing.
     * hasPos=false = world-global (full volume, centered). */
    public static void play(int sfxId, boolean hasPos, double doomX, double doomY) {
        play(sfxId, hasPos, doomX, doomY, 1.0);
    }

    /** gain: extra per-call trim under the slider (1.0 = the same loudness the engine
     * mixer gives a full-volume centered sound at the same slider position). */
    public static void play(int sfxId, boolean hasPos, double doomX, double doomY, double gain) {
        final Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || sfxId <= 0 || sfxId >= data.sounds.S_sfx.length) {
            return;
        }
        // land on DOOM's 16 volume steps, exactly like the engine side of the slider —
        // the two scales move together instead of drifting apart between detents
        double vol = Math.round(
            com.blackwithersteve.lattedoom.LatteDoomClient.doomSfxVolume() * 15.0) / 15.0 * gain;
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
                // S_AdjustSoundParams: pan by the angle between your facing and the
                // source — swing 96/128 like the original, so nothing goes fully deaf
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
