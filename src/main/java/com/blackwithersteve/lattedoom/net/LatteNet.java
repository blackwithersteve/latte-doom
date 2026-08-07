package com.blackwithersteve.lattedoom.net;

import com.blackwithersteve.lattedoom.play.MarineRoster;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

/**
 * SERVER-SYNC milestone, step 1: the marine-form roster. When a player transforms, the
 * server learns it (C2S), keeps the roster, and tells every client (S2C) — so OTHER
 * players render you as the WAD's PLAY marine, not Steve. Late joiners get the full
 * roster on join. This is the first piece of the mod's server side; the engine authority
 * itself (one simulation for everyone) rides the same channel later.
 */
public final class LatteNet {

    /** Client → server: my marine form switched. */
    public record FormC2S(boolean on) implements CustomPacketPayload {
        public static final Type<FormC2S> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath("lattedoom", "marine_form"));
        public static final StreamCodec<RegistryFriendlyByteBuf, FormC2S> CODEC =
            StreamCodec.composite(ByteBufCodecs.BOOL, FormC2S::on, FormC2S::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Server → clients: that player's marine form is now on/off. */
    public record FormS2C(UUID who, boolean on) implements CustomPacketPayload {
        public static final Type<FormS2C> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath("lattedoom", "marine_roster"));
        public static final StreamCodec<RegistryFriendlyByteBuf, FormS2C> CODEC =
            StreamCodec.composite(UUIDUtil.STREAM_CODEC, FormS2C::who,
                ByteBufCodecs.BOOL, FormS2C::on, FormS2C::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Server → joining client: Latte Doom is active here; this is the host's IWAD name
     * ("" = host hasn't got one). The client compares against its own and prints the
     * legal/compat disclaimer if needed. */
    public record HelloS2C(String hostIwad) implements CustomPacketPayload {
        public static final Type<HelloS2C> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath("lattedoom", "hello"));
        public static final StreamCodec<RegistryFriendlyByteBuf, HelloS2C> CODEC =
            StreamCodec.composite(ByteBufCodecs.STRING_UTF8, HelloS2C::hostIwad, HelloS2C::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Client → server: my engine raised (up=true) or dropped (up=false) this level. */
    public record LevelC2S(String map, double ox, double oy, double oz, boolean up)
        implements CustomPacketPayload {
        public static final Type<LevelC2S> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath("lattedoom", "level_c2s"));
        public static final StreamCodec<RegistryFriendlyByteBuf, LevelC2S> CODEC =
            StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, LevelC2S::map,
                ByteBufCodecs.DOUBLE, LevelC2S::ox,
                ByteBufCodecs.DOUBLE, LevelC2S::oy,
                ByteBufCodecs.DOUBLE, LevelC2S::oz,
                ByteBufCodecs.BOOL, LevelC2S::up,
                LevelC2S::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Server → clients: the shared level everyone should raise (or drop), and WHOSE
     * engine owns it (the owner keeps their own engine path; everyone else spectates). */
    public record LevelS2C(String map, double ox, double oy, double oz, boolean up, UUID owner)
        implements CustomPacketPayload {
        public static final Type<LevelS2C> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath("lattedoom", "level_s2c"));
        public static final StreamCodec<RegistryFriendlyByteBuf, LevelS2C> CODEC =
            StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, LevelS2C::map,
                ByteBufCodecs.DOUBLE, LevelS2C::ox,
                ByteBufCodecs.DOUBLE, LevelS2C::oy,
                ByteBufCodecs.DOUBLE, LevelS2C::oz,
                ByteBufCodecs.BOOL, LevelS2C::up,
                UUIDUtil.STREAM_CODEC, LevelS2C::owner,
                LevelS2C::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    // ---- the LIVE WORLD FEED (B1-B3): the owner's engine snapshot, ~20Hz. Carries tic,
    // sector heights + light, and the full mobj table — a spectating client drops it
    // into the SAME keyframe/interp machinery the owner uses, so doors glide, lights
    // flicker and monsters exist for everyone. STATE only, never WAD content (LEGAL.md).

    /** Client → server: the level owner's current world state. */
    public record SnapC2S(com.blackwithersteve.lattedoom.engine.WorldSnapshot snap)
        implements CustomPacketPayload {
        public static final Type<SnapC2S> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath("lattedoom", "snap_c2s"));
        public static final StreamCodec<RegistryFriendlyByteBuf, SnapC2S> CODEC =
            StreamCodec.of((buf, v) -> writeSnap(buf, v.snap()),
                buf -> new SnapC2S(readSnap(buf)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Server → spectating clients: same payload, rebroadcast. */
    public record SnapS2C(com.blackwithersteve.lattedoom.engine.WorldSnapshot snap)
        implements CustomPacketPayload {
        public static final Type<SnapS2C> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath("lattedoom", "snap_s2c"));
        public static final StreamCodec<RegistryFriendlyByteBuf, SnapS2C> CODEC =
            StreamCodec.of((buf, v) -> writeSnap(buf, v.snap()),
                buf -> new SnapS2C(readSnap(buf)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    private static void writeSnap(RegistryFriendlyByteBuf buf,
                                  com.blackwithersteve.lattedoom.engine.WorldSnapshot s) {
        buf.writeVarInt(s.tic);
        final int n = s.floorH != null ? s.floorH.length : 0;
        buf.writeVarInt(n);
        for (int i = 0; i < n; i++) {
            buf.writeShort((int) Math.round(s.floorH[i]));
            buf.writeShort((int) Math.round(s.ceilH[i]));
            buf.writeByte(Math.max(0, Math.min(255, s.light[i])));
        }
        final int rb = s.rbMobjId != null ? s.rbMobjId.length : 0;
        buf.writeVarInt(rb);
        for (int i = 0; i < rb; i++) {
            buf.writeLong(s.rbUuidMost[i]);
            buf.writeLong(s.rbUuidLeast[i]);
            buf.writeInt(s.rbMobjId[i]);
        }
        buf.writeVarInt(s.mobjCount);
        buf.writeVarInt(s.playerMobj);
        for (int i = 0; i < s.mobjCount; i++) {
            buf.writeInt(s.mId[i]);
            buf.writeInt((int) (s.mx[i] * 16.0));
            buf.writeInt((int) (s.my[i] * 16.0));
            buf.writeInt((int) (s.mz[i] * 16.0));
            buf.writeByte((int) (s.mAngleDeg[i] * 256.0 / 360.0) & 0xFF);
            buf.writeVarInt(s.mSprite[i]);
            buf.writeVarInt(s.mFrame[i]);
            buf.writeBoolean(s.mSolid[i]);
            buf.writeBoolean(s.mShootable != null && s.mShootable[i]);
            buf.writeByte(Math.min(127, (int) s.mRadius[i]));
        }
    }

    /** No level has this many sectors or objects, and a decoder must never size an
     * allocation from a number a client chose. Reading is abandoned past these. */
    private static final int MAX_WIRE_SECTORS = 65536;
    private static final int MAX_WIRE_MOBJS = 32768;
    private static final int MAX_WIRE_BODIES = 64;

    /** A packet whose declared counts are impossible, or which claims more content than the
     * bytes that arrived could hold. Decoding stops rather than allocating on trust. */
    private static final class BadSnapshot extends RuntimeException {
        BadSnapshot(String why) {
            super(why, null, false, false);
        }
    }

    /** Rejects a declared element count that is negative, absurd, or larger than the
     * remaining bytes could possibly encode. The readable-bytes test is what makes this
     * safe: it bounds the allocation by the packet that actually arrived, so no count can
     * ask for more memory than the sender was willing to pay to send. */
    private static int wireCount(RegistryFriendlyByteBuf buf, int n, int max, int minBytesEach,
                                 String what) {
        if (n < 0 || n > max || (minBytesEach > 0 && n > buf.readableBytes() / minBytesEach)) {
            throw new BadSnapshot(what + " count " + n + " is not plausible");
        }
        return n;
    }

    private static com.blackwithersteve.lattedoom.engine.WorldSnapshot readSnap(RegistryFriendlyByteBuf buf) {
        final var s = com.blackwithersteve.lattedoom.engine.WorldSnapshot.forRemote();
        s.tic = buf.readVarInt();
        final int n = wireCount(buf, buf.readVarInt(), MAX_WIRE_SECTORS, 5, "sector");
        s.floorH = new double[n];
        s.ceilH = new double[n];
        s.light = new short[n];
        s.floorPic = new short[n];
        s.ceilPic = new short[n];
        for (int i = 0; i < n; i++) {
            s.floorH[i] = buf.readShort();
            s.ceilH[i] = buf.readShort();
            s.light[i] = (short) buf.readUnsignedByte();
        }
        final int rb = wireCount(buf, buf.readVarInt(), MAX_WIRE_BODIES, 20, "remote body");
        if (rb > 0) {
            s.rbUuidMost = new long[rb];
            s.rbUuidLeast = new long[rb];
            s.rbMobjId = new int[rb];
            for (int i = 0; i < rb; i++) {
                s.rbUuidMost[i] = buf.readLong();
                s.rbUuidLeast[i] = buf.readLong();
                s.rbMobjId[i] = buf.readInt();
            }
        }
        final int m = wireCount(buf, buf.readVarInt(), MAX_WIRE_MOBJS, 12, "object");
        s.mobjCount = m;
        s.playerMobj = buf.readVarInt();
        s.mx = new double[m];
        s.my = new double[m];
        s.mz = new double[m];
        s.mAngleDeg = new double[m];
        s.mSprite = new int[m];
        s.mFrame = new int[m];
        s.mId = new int[m];
        s.mSolid = new boolean[m];
        s.mShootable = new boolean[m];
        s.mRadius = new double[m];
        for (int i = 0; i < m; i++) {
            s.mId[i] = buf.readInt();
            s.mx[i] = buf.readInt() / 16.0;
            s.my[i] = buf.readInt() / 16.0;
            s.mz[i] = buf.readInt() / 16.0;
            s.mAngleDeg[i] = buf.readUnsignedByte() * 360.0 / 256.0;
            s.mSprite[i] = buf.readVarInt();
            s.mFrame[i] = buf.readVarInt();
            s.mSolid[i] = buf.readBoolean();
            s.mShootable[i] = buf.readBoolean();
            s.mRadius[i] = buf.readByte();
        }
        return s;
    }

    // ---- PRESENCE (B4): a spectator's body, inputs and triggers, upstream to the level
    // owner's engine — where it becomes players[1..3]. Monsters hunt them, their shots
    // are real, doors open for them. Position/angle/buttons only — state, not content.

    /** Client → server: my in-level presence this tick. */
    public record PresenceC2S(double x, double y, double z, double angleDeg,
                              int buttons, int slot, int healthMc, int[][] crossings)
        implements CustomPacketPayload {
        public static final Type<PresenceC2S> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath("lattedoom", "presence_c2s"));
        public static final StreamCodec<RegistryFriendlyByteBuf, PresenceC2S> CODEC =
            StreamCodec.of((buf, v) -> {
                writePresence(buf, v.x(), v.y(), v.z(), v.angleDeg(), v.buttons(),
                    v.slot(), v.healthMc(), v.crossings());
            }, buf -> {
                final double[] d = new double[4];
                final int[] iv = new int[3];
                final int[][] cr = readPresence(buf, d, iv);
                return new PresenceC2S(d[0], d[1], d[2], d[3], iv[0], iv[1], iv[2], cr);
            });

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Server → the level owner: who this presence belongs to, plus the same data. */
    public record PresenceS2C(UUID who, double x, double y, double z, double angleDeg,
                              int buttons, int slot, int healthMc, int[][] crossings)
        implements CustomPacketPayload {
        public static final Type<PresenceS2C> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath("lattedoom", "presence_s2c"));
        public static final StreamCodec<RegistryFriendlyByteBuf, PresenceS2C> CODEC =
            StreamCodec.of((buf, v) -> {
                buf.writeLong(v.who().getMostSignificantBits());
                buf.writeLong(v.who().getLeastSignificantBits());
                writePresence(buf, v.x(), v.y(), v.z(), v.angleDeg(), v.buttons(),
                    v.slot(), v.healthMc(), v.crossings());
            }, buf -> {
                final UUID who = new UUID(buf.readLong(), buf.readLong());
                final double[] d = new double[4];
                final int[] iv = new int[3];
                final int[][] cr = readPresence(buf, d, iv);
                return new PresenceS2C(who, d[0], d[1], d[2], d[3], iv[0], iv[1], iv[2], cr);
            });

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    private static void writePresence(RegistryFriendlyByteBuf buf, double x, double y,
                                      double z, double ang, int buttons, int slot,
                                      int healthMc, int[][] crossings) {
        buf.writeInt((int) (x * 16.0));
        buf.writeInt((int) (y * 16.0));
        buf.writeInt((int) (z * 16.0));
        buf.writeFloat((float) ang);
        buf.writeByte(buttons);
        buf.writeByte(slot);
        buf.writeShort(healthMc);
        final int n = crossings != null ? crossings.length : 0;
        buf.writeVarInt(n);
        for (int i = 0; i < n; i++) {
            buf.writeVarInt(crossings[i][0]);
            buf.writeBoolean(crossings[i][1] != 0);
        }
    }

    private static int[][] readPresence(RegistryFriendlyByteBuf buf, double[] d, int[] iv) {
        d[0] = buf.readInt() / 16.0;
        d[1] = buf.readInt() / 16.0;
        d[2] = buf.readInt() / 16.0;
        d[3] = buf.readFloat();
        iv[0] = buf.readByte();
        iv[1] = buf.readByte();
        iv[2] = buf.readShort();
        final int n = buf.readVarInt();
        final int[][] cr = new int[n][];
        for (int i = 0; i < n; i++) {
            cr[i] = new int[]{buf.readVarInt(), buf.readBoolean() ? 1 : 0};
        }
        return cr;
    }

    /** Client → server: my DOOM weapon hit this Minecraft entity (mob or player) for
     * this many DOOM hit points. Server validates and applies attributed damage. */
    public record HitC2S(int entityId, int damageHp) implements CustomPacketPayload {
        public static final Type<HitC2S> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath("lattedoom", "hit_c2s"));
        public static final StreamCodec<RegistryFriendlyByteBuf, HitC2S> CODEC =
            StreamCodec.composite(ByteBufCodecs.VAR_INT, HitC2S::entityId,
                ByteBufCodecs.VAR_INT, HitC2S::damageHp, HitC2S::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Client → server: my MINECRAFT attack (fist/sword/arrow) hit this DOOM thing.
     * projectileId ≥ 0 = the arrow to retire. Routed to the level owner's engine. */
    public record DoomHitC2S(int mobjId, int damageHp, int projectileId)
        implements CustomPacketPayload {
        public static final Type<DoomHitC2S> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath("lattedoom", "doomhit_c2s"));
        public static final StreamCodec<RegistryFriendlyByteBuf, DoomHitC2S> CODEC =
            StreamCodec.composite(ByteBufCodecs.VAR_INT, DoomHitC2S::mobjId,
                ByteBufCodecs.VAR_INT, DoomHitC2S::damageHp,
                ByteBufCodecs.VAR_INT, DoomHitC2S::projectileId, DoomHitC2S::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Server → the level owner: apply that hit in your engine, attributed to them. */
    public record DoomHitS2C(UUID attacker, int mobjId, int damageHp)
        implements CustomPacketPayload {
        public static final Type<DoomHitS2C> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath("lattedoom", "doomhit_s2c"));
        public static final StreamCodec<RegistryFriendlyByteBuf, DoomHitS2C> CODEC =
            StreamCodec.composite(UUIDUtil.STREAM_CODEC, DoomHitS2C::attacker,
                ByteBufCodecs.VAR_INT, DoomHitS2C::mobjId,
                ByteBufCodecs.VAR_INT, DoomHitS2C::damageHp, DoomHitS2C::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Client → server: the DOOM engine dealt damage/healing to this Minecraft player.
     * Sent by the engine-owning client; the SERVER applies it (works for LAN guests —
     * the old direct integrated-server path was null off the host and ate all damage). */
    public record PlayerDamageC2S(UUID target, int dmgHp, int healHp, double kx, double kz,
                                  boolean blocked)
        implements CustomPacketPayload {
        public static final Type<PlayerDamageC2S> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath("lattedoom", "pdmg_c2s"));
        public static final StreamCodec<RegistryFriendlyByteBuf, PlayerDamageC2S> CODEC =
            StreamCodec.composite(UUIDUtil.STREAM_CODEC, PlayerDamageC2S::target,
                ByteBufCodecs.VAR_INT, PlayerDamageC2S::dmgHp,
                ByteBufCodecs.VAR_INT, PlayerDamageC2S::healHp,
                ByteBufCodecs.DOUBLE, PlayerDamageC2S::kx,
                ByteBufCodecs.DOUBLE, PlayerDamageC2S::kz,
                ByteBufCodecs.BOOL, PlayerDamageC2S::blocked,
                PlayerDamageC2S::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Client → server: I (plain Steve) scavenged a DOOM item — apply the configured
     * Minecraft conversion to ME. The server clamps everything; the sender can only
     * ever gift itself modest amounts (heal/food/items), so no attribution needed. */
    public record ScavengeC2S(int healHp, int foodPts, String itemId, int count)
        implements CustomPacketPayload {
        public static final Type<ScavengeC2S> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath("lattedoom", "scavenge_c2s"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ScavengeC2S> CODEC =
            StreamCodec.composite(ByteBufCodecs.VAR_INT, ScavengeC2S::healHp,
                ByteBufCodecs.VAR_INT, ScavengeC2S::foodPts,
                ByteBufCodecs.STRING_UTF8, ScavengeC2S::itemId,
                ByteBufCodecs.VAR_INT, ScavengeC2S::count,
                ScavengeC2S::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Client → server: place the block I'm holding at this cell of the DOOM level dim
     * (B2). The client ray-marched the DRAWN geometry to pick the cell — the server
     * only ever places what the sender's own hand holds, next to the sender. */
    public record PlaceBlockC2S(net.minecraft.core.BlockPos pos, boolean offHand)
        implements CustomPacketPayload {
        public static final Type<PlaceBlockC2S> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath("lattedoom", "place_c2s"));
        public static final StreamCodec<RegistryFriendlyByteBuf, PlaceBlockC2S> CODEC =
            StreamCodec.composite(net.minecraft.core.BlockPos.STREAM_CODEC, PlaceBlockC2S::pos,
                ByteBufCodecs.BOOL, PlaceBlockC2S::offHand,
                PlaceBlockC2S::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Client → server: teleport ME to this world position. The server applies it
     * authoritatively (ServerPlayer.teleportTo) — no op/cheats needed, no "moved
     * wrongly" rollback, and crucially NO /tp chat command spamming the log. */
    public record TeleportC2S(double x, double y, double z) implements CustomPacketPayload {
        public static final Type<TeleportC2S> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath("lattedoom", "tp_c2s"));
        public static final StreamCodec<RegistryFriendlyByteBuf, TeleportC2S> CODEC =
            StreamCodec.composite(ByteBufCodecs.DOUBLE, TeleportC2S::x,
                ByteBufCodecs.DOUBLE, TeleportC2S::y,
                ByteBufCodecs.DOUBLE, TeleportC2S::z,
                TeleportC2S::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Client → server: put ME into the private DOOM-level dimension at this position (a
     * void world where only the raised level renders — the perf win). The server does the
     * cross-dimension teleport authoritatively; if the datapack dimension is somehow absent
     * it degrades to a same-dimension teleport, i.e. the old over-the-overworld behaviour. */
    public record EnterLevelDimC2S(double x, double y, double z) implements CustomPacketPayload {
        public static final Type<EnterLevelDimC2S> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath("lattedoom", "enter_level_dim"));
        public static final StreamCodec<RegistryFriendlyByteBuf, EnterLevelDimC2S> CODEC =
            StreamCodec.composite(ByteBufCodecs.DOUBLE, EnterLevelDimC2S::x,
                ByteBufCodecs.DOUBLE, EnterLevelDimC2S::y,
                ByteBufCodecs.DOUBLE, EnterLevelDimC2S::z,
                EnterLevelDimC2S::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Client → server: take ME back to the overworld at this position (leaving the level). */
    public record LeaveLevelDimC2S(double x, double y, double z) implements CustomPacketPayload {
        public static final Type<LeaveLevelDimC2S> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath("lattedoom", "leave_level_dim"));
        public static final StreamCodec<RegistryFriendlyByteBuf, LeaveLevelDimC2S> CODEC =
            StreamCodec.composite(ByteBufCodecs.DOUBLE, LeaveLevelDimC2S::x,
                ByteBufCodecs.DOUBLE, LeaveLevelDimC2S::y,
                ByteBufCodecs.DOUBLE, LeaveLevelDimC2S::z,
                LeaveLevelDimC2S::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** The private void dimension the levels are rendered inside (datapack-defined in
     * data/lattedoom/dimension/doom_level.json). Only that dimension's teleports target it. */
    public static final net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level>
        DOOM_LEVEL_DIM = net.minecraft.resources.ResourceKey.create(
            net.minecraft.core.registries.Registries.DIMENSION,
            Identifier.fromNamespaceAndPath("lattedoom", "doom_level"));

    /** Sound EVENTS from the world owner's engine: {sfxId, doomX, doomY, hasPos} each.
     * Ids and coordinates only — every client renders audio from its OWN WAD. */
    public record SoundsC2S(int[][] events) implements CustomPacketPayload {
        public static final Type<SoundsC2S> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath("lattedoom", "sounds_c2s"));
        public static final StreamCodec<RegistryFriendlyByteBuf, SoundsC2S> CODEC =
            StreamCodec.of((buf, v) -> writeSounds(buf, v.events()),
                buf -> new SoundsC2S(readSounds(buf)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Server → spectating clients: same events, rebroadcast. */
    public record SoundsS2C(int[][] events) implements CustomPacketPayload {
        public static final Type<SoundsS2C> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath("lattedoom", "sounds_s2c"));
        public static final StreamCodec<RegistryFriendlyByteBuf, SoundsS2C> CODEC =
            StreamCodec.of((buf, v) -> writeSounds(buf, v.events()),
                buf -> new SoundsS2C(readSounds(buf)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    private static void writeSounds(RegistryFriendlyByteBuf buf, int[][] events) {
        final int n = Math.min(events.length, 32);
        buf.writeVarInt(n);
        for (int i = 0; i < n; i++) {
            buf.writeVarInt(events[i][0]);
            buf.writeShort(events[i][1]);
            buf.writeShort(events[i][2]);
            buf.writeBoolean(events[i][3] != 0);
        }
    }

    private static int[][] readSounds(RegistryFriendlyByteBuf buf) {
        final int n = Math.min(buf.readVarInt(), 32);
        final int[][] out = new int[n][];
        for (int i = 0; i < n; i++) {
            out[i] = new int[]{buf.readVarInt(), buf.readShort(), buf.readShort(),
                buf.readBoolean() ? 1 : 0};
        }
        return out;
    }

    /** The shared level the server currently knows (null = none), and who owns it. */
    private static volatile LevelS2C serverLevel;
    private static volatile UUID serverLevelOwner;

    /** Per-player self-heal rate window {windowStartTick, healedThisWindow}. MC's heal()
     * already clamps overheal to max health, so this only defends co-op against a MODIFIED
     * client spamming full-heals to be unkillable — legit medikit/soulsphere pickups (the
     * engine's real heals) stay far under the cap. */
    private static final java.util.Map<UUID, long[]> HEAL_WINDOW =
        new java.util.concurrent.ConcurrentHashMap<>();
    /** How far a self-teleport may move a player outside the level dimension. Covers the
     * delivery into a level and the return from one, and nothing else. */
    private static final double TELEPORT_MAX_HOP = 64.0;

    /** A map lump name as the engine writes them: ExMy or MAPxx, letters and digits only. */
    private static final java.util.regex.Pattern MAP_NAME =
        java.util.regex.Pattern.compile("[A-Za-z0-9_]{1,32}");

    private static boolean finite(double v) {
        return Double.isFinite(v) && Math.abs(v) < 3.0e7;
    }

    /** Engine hit points a single player may be billed per rolling second. DOOM cannot
     * deal anything near this in 35 tics; a modified client can. */
    /** Scavenge packets honoured per player per rolling second. Walking over items cannot
     * produce anywhere near this; a flood can. */
    private static final int SCAVENGE_CAP_PER_SEC = 12;
    private static final java.util.Map<UUID, long[]> scavWindow =
        new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Blocks players have placed inside the level dimension. That dimension persists across
     * level loads and each level is raised at its own origin, so without this the previous
     * map's blocks are left floating in the empty world around the new one.
     */
    private static final java.util.Set<net.minecraft.core.BlockPos> placedBlocks =
        java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** Removes every block placed inside the level dimension. */
    private static void clearPlacedBlocks(net.minecraft.server.MinecraftServer server) {
        if (placedBlocks.isEmpty()) {
            return;
        }
        final net.minecraft.server.level.ServerLevel lvl = server.getLevel(DOOM_LEVEL_DIM);
        if (lvl != null) {
            for (net.minecraft.core.BlockPos p : placedBlocks) {
                if (!lvl.getBlockState(p).isAir()) {
                    lvl.setBlock(p, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 3);
                }
            }
        }
        placedBlocks.clear();
    }

    /** How far from a raised level's origin an entry may land, in blocks. */
    private static final double LEVEL_ENTRY_RADIUS = 512.0;

    private static final int DMG_CAP_PER_SEC = 700;
    private static final java.util.Map<UUID, long[]> dmgWindow =
        new java.util.concurrent.ConcurrentHashMap<>();

    private static final int HEAL_CAP_PER_SEC = 200; // DOOM HP per second (/5 = MC half-hearts)

    /** What IWAD this side has (client scan); set at client init. */
    private static java.util.function.Supplier<java.nio.file.Path> localIwad = () -> null;

    public static void init(java.util.function.Supplier<java.nio.file.Path> iwadSupplier) {
        localIwad = iwadSupplier;
        PayloadTypeRegistry.serverboundPlay().register(FormC2S.TYPE, FormC2S.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(FormS2C.TYPE, FormS2C.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(HelloS2C.TYPE, HelloS2C.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(LevelC2S.TYPE, LevelC2S.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(LevelS2C.TYPE, LevelS2C.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(SnapC2S.TYPE, SnapC2S.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(SnapS2C.TYPE, SnapS2C.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(PresenceC2S.TYPE, PresenceC2S.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(PresenceS2C.TYPE, PresenceS2C.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(HitC2S.TYPE, HitC2S.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(DoomHitC2S.TYPE, DoomHitC2S.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(DoomHitS2C.TYPE, DoomHitS2C.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(PlayerDamageC2S.TYPE, PlayerDamageC2S.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(ScavengeC2S.TYPE, ScavengeC2S.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(PlaceBlockC2S.TYPE, PlaceBlockC2S.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(TeleportC2S.TYPE, TeleportC2S.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(EnterLevelDimC2S.TYPE, EnterLevelDimC2S.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(LeaveLevelDimC2S.TYPE, LeaveLevelDimC2S.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(SoundsC2S.TYPE, SoundsC2S.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(SoundsS2C.TYPE, SoundsS2C.CODEC);

        // ---- server side (integrated or LAN host; dedicated comes with the milestone) ----
        ServerPlayNetworking.registerGlobalReceiver(FormC2S.TYPE, (payload, context) -> {
            final ServerPlayer who = context.player();
            if (payload.on()) {
                MarineRoster.SERVER.add(who.getUUID());
            } else {
                MarineRoster.SERVER.remove(who.getUUID());
            }
            broadcast(context.server(), new FormS2C(who.getUUID(), payload.on()));
        });
        ServerPlayNetworking.registerGlobalReceiver(LevelC2S.TYPE, (payload, context) -> {
            final ServerPlayer who = context.player();
            if (payload.up()) {
                // Ownership is the authorisation token for the damage, snapshot, sound and
                // presence lanes, so claiming it has to be validated like any other
                // privileged action rather than granted to whoever asks last.
                final String map = payload.map();
                if (map.isEmpty() || map.length() > 32 || !MAP_NAME.matcher(map).matches()) {
                    return; // a raised level names a real map lump
                }
                if (!finite(payload.ox()) || !finite(payload.oy()) || !finite(payload.oz())) {
                    return; // the origin is broadcast to every client and drives their meshes
                }
                if (who.isSpectator()) {
                    return;
                }
                // Taking a level over from another player is a designed feature: loading
                // while spectating someone's level makes yours the world. So the test is not
                // whether the current owner is still there, it is whether the claimant has
                // any business claiming. An unowned level may be claimed by anyone, since
                // that is an ordinary warp from the overworld. Taking over an owned one
                // requires already standing in it, which a spectator does and a client
                // Taking over another player's level is a designed feature and happens both
                // from inside it, when a spectator loads their own, and from the overworld,
                // when an ordinary warp lands while someone else happens to own one. Neither
                // can be refused without breaking a normal flow, so ownership is granted on
                // the checks above and the privileged lanes it unlocks are guarded on their
                // own terms instead.
                final LevelS2C before = serverLevel;
                serverLevel = new LevelS2C(map, payload.ox(), payload.oy(),
                    payload.oz(), true, who.getUUID());
                serverLevelOwner = who.getUUID();
                if (before == null || !before.map().equals(map)
                    || before.ox() != payload.ox() || before.oz() != payload.oz()) {
                    clearPlacedBlocks(context.server()); // a different level stands here now
                }
            } else {
                // only the current owner can take the level down (a second player's
                // engine noise must not strip the one everybody is standing in)
                if (!who.getUUID().equals(serverLevelOwner)) {
                    return;
                }
                serverLevel = null;
                serverLevelOwner = null;
                clearPlacedBlocks(context.server());
            }
            for (ServerPlayer p : PlayerLookup.all(context.server())) {
                if (ServerPlayNetworking.canSend(p, LevelS2C.TYPE)) {
                    ServerPlayNetworking.send(p, payload.up()
                        ? serverLevel : new LevelS2C("", 0, 0, 0, false, who.getUUID()));
                }
            }
        });
        ServerPlayNetworking.registerGlobalReceiver(SnapC2S.TYPE, (payload, context) -> {
            // only the level OWNER's world feed is the truth; anyone else's is noise
            if (!context.player().getUUID().equals(serverLevelOwner)) {
                return;
            }
            final UUID owner = context.player().getUUID();
            for (ServerPlayer p : PlayerLookup.all(context.server())) {
                if (!p.getUUID().equals(owner)
                    && ServerPlayNetworking.canSend(p, SnapS2C.TYPE)) {
                    ServerPlayNetworking.send(p, new SnapS2C(payload.snap()));
                }
            }
        });
        ServerPlayNetworking.registerGlobalReceiver(SoundsC2S.TYPE, (payload, context) -> {
            // only the world owner's soundscape is broadcast, to everyone else
            final UUID owner = serverLevelOwner;
            if (owner == null || !owner.equals(context.player().getUUID())) {
                return;
            }
            for (ServerPlayer p : PlayerLookup.all(context.server())) {
                if (!p.getUUID().equals(owner)
                    && ServerPlayNetworking.canSend(p, SoundsS2C.TYPE)) {
                    ServerPlayNetworking.send(p, new SoundsS2C(payload.events()));
                }
            }
        });
        ServerPlayNetworking.registerGlobalReceiver(PresenceC2S.TYPE, (payload, context) -> {
            // a spectator's presence goes to ONE place: the engine that owns the world
            final UUID owner = serverLevelOwner;
            if (owner == null || owner.equals(context.player().getUUID())) {
                return;
            }
            final ServerPlayer host = context.server().getPlayerList().getPlayer(owner);
            if (host != null && ServerPlayNetworking.canSend(host, PresenceS2C.TYPE)) {
                ServerPlayNetworking.send(host, new PresenceS2C(context.player().getUUID(),
                    payload.x(), payload.y(), payload.z(), payload.angleDeg(),
                    payload.buttons(), payload.slot(), payload.healthMc(), payload.crossings()));
            }
        });
        ServerPlayNetworking.registerGlobalReceiver(HitC2S.TYPE, (payload, context) -> {
            final ServerPlayer shooter = context.player();
            // only transformed marines shoot DOOM bullets, and only plausible ones
            if (!MarineRoster.SERVER.contains(shooter.getUUID())
                || payload.damageHp() <= 0 || payload.damageHp() > 120) {
                return;
            }
            final var target = shooter.level().getEntity(payload.entityId());
            if (!(target instanceof net.minecraft.world.entity.LivingEntity living)
                || !living.isAlive()
                || living.distanceToSqr(shooter) > 80.0 * 80.0) {
                return;
            }
            living.invulnerableTime = 0; // DOOM has no mercy frames — chaingun means it
            living.hurtServer((net.minecraft.server.level.ServerLevel) living.level(),
                living.damageSources().playerAttack(shooter), payload.damageHp() / 5.0f);
        });
        ServerPlayNetworking.registerGlobalReceiver(PlayerDamageC2S.TYPE, (payload, context) -> {
            if (payload.dmgHp() < 0 || payload.dmgHp() > 1000
                || payload.healHp() < 0 || payload.healHp() > 1000) {
                return;
            }
            // world authority (the level owner) may bill anyone; anyone may bill SELF
            // (their own suit engine's doing)
            final UUID sender = context.player().getUUID();
            if (!sender.equals(serverLevelOwner) && !sender.equals(payload.target())) {
                return;
            }
            final ServerPlayer sp = context.server().getPlayerList().getPlayer(payload.target());
            if (sp == null || sp.getAbilities().invulnerable || sp.isSpectator()) {
                return;
            }
            // Engine damage is only meaningful to a participant. Without this the level
            // owner can bill any player on the server, in any dimension, at any distance.
            if (!sender.equals(payload.target())
                && !sp.level().dimension().equals(DOOM_LEVEL_DIM)) {
                return;
            }
            // Rate-cap per target over a rolling second, the same shape as the heal cap.
            // Real engine damage is bounded by what DOOM can deal in 35 tics; a flood is
            // not, and the damage path had no ceiling at all.
            if (payload.dmgHp() > 0) {
                final long nowTick = context.server().getTickCount();
                final long[] w = dmgWindow.computeIfAbsent(payload.target(),
                    k -> new long[]{nowTick, 0});
                if (nowTick - w[0] >= 20L) {
                    w[0] = nowTick;
                    w[1] = 0;
                }
                if (w[1] >= DMG_CAP_PER_SEC) {
                    return;
                }
                w[1] += payload.dmgHp();
            }
            if (payload.dmgHp() > 0 && payload.blocked() && sp.isBlocking()) {
                // A raised shield facing the hit absorbs it. The client decided the arc from
                // the engine's knockback vector, since engine damage has no attacker entity
                // for Minecraft's own blocking test to read, and the server confirms the
                // shield is actually up before honouring it.
                sp.level().playSound(null, sp.getX(), sp.getY(), sp.getZ(),
                    net.minecraft.sounds.SoundEvents.SHIELD_BLOCK,
                    net.minecraft.sounds.SoundSource.PLAYERS, 1.0f, 0.8f + 0.4f * sp.getRandom().nextFloat());
                final var shield = sp.getUseItem();
                if (!shield.isEmpty() && payload.dmgHp() >= 15) {
                    shield.hurtAndBreak(1, sp, net.minecraft.world.entity.EquipmentSlot.OFFHAND);
                }
                if (payload.kx() != 0 || payload.kz() != 0) {
                    sp.push(payload.kx() * 0.3, 0.1, payload.kz() * 0.3);
                    sp.hurtMarked = true;
                }
            } else if (payload.dmgHp() > 0) {
                sp.invulnerableTime = 0; // DOOM has no mercy frames
                // magic, not generic: the ENGINE already ran DOOM's whole damage pipeline
                // (skill scaling, armor soak, the dice) — MC armor/shields must not reduce
                // it a second time ("the damage doesn't feel right"). Magic bypasses armor.
                sp.hurtServer((net.minecraft.server.level.ServerLevel) sp.level(),
                    sp.damageSources().magic(), payload.dmgHp() / 5.0f);
                if (payload.kx() != 0 || payload.kz() != 0) {
                    sp.push(payload.kx(), 0.35, payload.kz());
                    sp.hurtMarked = true;
                }
            }
            if (payload.healHp() > 0) {
                // rate-cap the self-heal per rolling 1s window (anti heal-flood, co-op)
                final long tick = context.server().getTickCount();
                final long[] w = HEAL_WINDOW.computeIfAbsent(payload.target(),
                    k -> new long[]{tick, 0});
                if (tick - w[0] >= 20L) { // new second
                    w[0] = tick;
                    w[1] = 0;
                }
                final int allowed = (int) Math.max(0, HEAL_CAP_PER_SEC - w[1]);
                final int heal = Math.min(payload.healHp(), allowed);
                if (heal > 0) {
                    w[1] += heal;
                    sp.heal(heal / 5.0f);
                }
            }
        });
        ServerPlayNetworking.registerGlobalReceiver(ScavengeC2S.TYPE, (payload, context) -> {
            // Steve scavenging: self-gifting only, hard-clamped (one item's worth per
            // packet — a medikit is 10 hp, a bullet box 16 arrows; 64 covers any sane
            // config line). The engine consumed the doom item; we hand over the MC side.
            final ServerPlayer sp = context.player();
            if (sp == null || sp.isSpectator()) {
                return;
            }
            // Per-packet clamps bound one pickup; they do not bound a thousand of them a
            // second. A rolling budget does, and real scavenging stays far under it: the
            // engine can only hand over items the player actually walked onto.
            final long nowTick = context.server().getTickCount();
            final long[] w = scavWindow.computeIfAbsent(sp.getUUID(),
                k -> new long[]{nowTick, 0});
            if (nowTick - w[0] >= 20L) {
                w[0] = nowTick;
                w[1] = 0;
            }
            if (w[1] >= SCAVENGE_CAP_PER_SEC) {
                return;
            }
            w[1]++;
            final int heal = Math.max(0, Math.min(80, payload.healHp()));
            final int food = Math.max(0, Math.min(20, payload.foodPts()));
            final int count = Math.max(0, Math.min(64, payload.count()));
            if (heal > 0) {
                sp.heal(heal);
            }
            if (food > 0) {
                sp.getFoodData().eat(food, 0.6f);
            }
            if (count > 0 && !payload.itemId().isEmpty()) {
                final Identifier id = Identifier.tryParse(payload.itemId());
                final net.minecraft.world.item.Item item = id == null ? null
                    : net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(id);
                if (item != null && item != net.minecraft.world.item.Items.AIR) {
                    sp.getInventory().placeItemBackInInventory(
                        new net.minecraft.world.item.ItemStack(item, count));
                }
            }
        });
        ServerPlayNetworking.registerGlobalReceiver(PlaceBlockC2S.TYPE, (payload, context) -> {
            // B2 block placement: the level dim only, close by, replaceable target, no
            // living body in the cell; place exactly what the sender's own hand holds
            // (default state — no orientation contexts yet) and consume it in survival.
            final ServerPlayer sp = context.player();
            if (sp == null || sp.isSpectator()
                || !sp.level().dimension().equals(DOOM_LEVEL_DIM)) {
                return;
            }
            final net.minecraft.core.BlockPos pos = payload.pos();
            if (sp.getEyePosition().distanceToSqr(
                pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) > 49.0) {
                return;
            }
            final var hand = payload.offHand() ? net.minecraft.world.InteractionHand.OFF_HAND
                : net.minecraft.world.InteractionHand.MAIN_HAND;
            final net.minecraft.world.item.ItemStack held = sp.getItemInHand(hand);
            if (!(held.getItem() instanceof net.minecraft.world.item.BlockItem bi)
                || held.isEmpty()) {
                return;
            }
            final net.minecraft.server.level.ServerLevel lvl =
                (net.minecraft.server.level.ServerLevel) sp.level();
            if (!lvl.getBlockState(pos).canBeReplaced()) {
                return;
            }
            final net.minecraft.world.phys.AABB cell = new net.minecraft.world.phys.AABB(
                pos.getX(), pos.getY(), pos.getZ(),
                pos.getX() + 1.0, pos.getY() + 1.0, pos.getZ() + 1.0);
            if (!lvl.getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class, cell)
                .isEmpty()) {
                return;
            }
            final var state = bi.getBlock().defaultBlockState();
            lvl.setBlock(pos, state, 3);
            placedBlocks.add(pos.immutable());
            final var snd = state.getSoundType();
            lvl.playSound(null, pos, snd.getPlaceSound(),
                net.minecraft.sounds.SoundSource.BLOCKS,
                (snd.getVolume() + 1.0f) / 2.0f, snd.getPitch() * 0.8f);
            if (!sp.getAbilities().instabuild) {
                held.shrink(1);
            }
        });
        ServerPlayNetworking.registerGlobalReceiver(TeleportC2S.TYPE, (payload, context) -> {
            // authoritative self-teleport: the server moves the player, so no anti-cheat
            // rollback and no /tp permission needed. Bounds-guarded against NaN/garbage.
            final double x = payload.x(), y = payload.y(), z = payload.z();
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                || Math.abs(x) > 3.0e7 || Math.abs(z) > 3.0e7 || y < -1024 || y > 2048) {
                return;
            }
            final ServerPlayer tp = context.player();
            if (tp.isSpectator()) {
                return;
            }
            // A self-teleport is only legitimate as part of a level session. Inside the
            // level dimension it may go anywhere, since a warp crosses a whole map; in any
            // other world it is capped to a short hop, which covers the delivery into a
            // level and the return from one. Without this a modified client could send a
            // single packet and travel anywhere on a shared server.
            if (!tp.level().dimension().equals(DOOM_LEVEL_DIM)
                && tp.distanceToSqr(x, y, z) > TELEPORT_MAX_HOP * TELEPORT_MAX_HOP) {
                return;
            }
            tp.teleportTo(x, y, z);
        });
        ServerPlayNetworking.registerGlobalReceiver(EnterLevelDimC2S.TYPE, (payload, context) -> {
            final double x = payload.x(), y = payload.y(), z = payload.z();
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                || Math.abs(x) > 3.0e7 || Math.abs(z) > 3.0e7 || y < -1024 || y > 2048) {
                return;
            }
            final ServerPlayer p = context.player();
            if (p.isSpectator()) {
                return;
            }
            // Only into a level that is actually raised. Without this the packet is a free
            // teleport into an empty dimension with no floor, which is also how a client
            // could park itself somewhere no other player can reach.
            // Bound the destination to a raised level when there is one. A suit boot raises
            // its map without announcing it, and /doomstart sends this packet before the
            // announce, so requiring a level here refused the first entry outright. When no
            // level is known, fall back to bounding the hop like an ordinary self-teleport.
            final LevelS2C live = serverLevel;
            if (live != null) {
                if (Math.abs(x - live.ox()) > LEVEL_ENTRY_RADIUS
                    || Math.abs(z - live.oz()) > LEVEL_ENTRY_RADIUS) {
                    return; // a level start is near its own origin
                }
            } else if (p.distanceToSqr(x, y, z) > LEVEL_ENTRY_RADIUS * LEVEL_ENTRY_RADIUS) {
                return;
            }
            final net.minecraft.server.level.ServerLevel target =
                context.server().getLevel(DOOM_LEVEL_DIM);
            if (target == null) {
                // datapack dimension missing (should never happen) — degrade to the old
                // same-dimension behaviour so /load still puts the player in the level
                p.teleportTo(x, y, z);
                return;
            }
            p.teleportTo(target, x, y, z, java.util.Set.of(), p.getYRot(), p.getXRot(), false);
        });
        ServerPlayNetworking.registerGlobalReceiver(LeaveLevelDimC2S.TYPE, (payload, context) -> {
            // Only meaningful from inside the level dimension. Elsewhere it is a free
            // teleport with a different name.
            if (!context.player().level().dimension().equals(DOOM_LEVEL_DIM)) {
                return;
            }
            // The destination is a remembered overworld position, not a free choice. Bound
            // it to the world border so this cannot become a long-range teleport.
            if (!finite(payload.x()) || !finite(payload.y()) || !finite(payload.z())) {
                return;
            }
            final double x = payload.x(), y = payload.y(), z = payload.z();
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                || Math.abs(x) > 3.0e7 || Math.abs(z) > 3.0e7 || y < -1024 || y > 2048) {
                return;
            }
            final ServerPlayer p = context.player();
            p.teleportTo(context.server().overworld(), x, y, z, java.util.Set.of(),
                p.getYRot(), p.getXRot(), false);
        });
        ServerPlayNetworking.registerGlobalReceiver(DoomHitC2S.TYPE, (payload, context) -> {
            if (payload.damageHp() <= 0 || payload.damageHp() > 200) {
                return;
            }
            // Damage is routed into another client's engine, so the sender has to be a
            // participant: in the level dimension, alive and not spectating. Otherwise any
            // client could drive the level owner's engine from the overworld.
            final ServerPlayer hitter = context.player();
            if (hitter.isSpectator() || !hitter.isAlive()
                || !hitter.level().dimension().equals(DOOM_LEVEL_DIM)) {
                return;
            }
            // retire a spent arrow authoritatively
            if (payload.projectileId() >= 0) {
                final var proj = context.player().level().getEntity(payload.projectileId());
                if (proj instanceof net.minecraft.world.entity.projectile.arrow.AbstractArrow) {
                    proj.discard();
                }
            }
            // the engine lives on the level owner's client: forward the hit there
            // (mobjId -1 = the projectile just hit level geometry; nothing to damage)
            if (payload.mobjId() < 0) {
                return;
            }
            final UUID owner = serverLevelOwner;
            final ServerPlayer ownerPlayer = owner != null
                ? context.server().getPlayerList().getPlayer(owner) : null;
            if (ownerPlayer != null && ServerPlayNetworking.canSend(ownerPlayer, DoomHitS2C.TYPE)) {
                ServerPlayNetworking.send(ownerPlayer, new DoomHitS2C(
                    context.player().getUUID(), payload.mobjId(), payload.damageHp()));
            }
        });
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            // a player who logged out INSIDE the level dimension would rejoin into an empty
            // void (the level isn't raised for them anymore) — never strand them: bring them
            // to the overworld spawn on join.
            if (handler.player.level().dimension().identifier().equals(DOOM_LEVEL_DIM.identifier())) {
                final net.minecraft.server.level.ServerLevel ow = server.overworld();
                final net.minecraft.core.BlockPos sp = ow.getRespawnData().pos();
                handler.player.teleportTo(ow, sp.getX() + 0.5, sp.getY(), sp.getZ() + 0.5,
                    java.util.Set.of(), handler.player.getYRot(), handler.player.getXRot(), false);
            }
            // the WAD handshake first (on a LAN host, "the server's IWAD" is the host's)
            if (ServerPlayNetworking.canSend(handler.player, HelloS2C.TYPE)) {
                final java.nio.file.Path iwad = localIwad.get();
                ServerPlayNetworking.send(handler.player,
                    new HelloS2C(iwad != null ? iwad.getFileName().toString() : ""));
            }
            for (UUID u : MarineRoster.SERVER) {
                sendTo(handler.player, new FormS2C(u, true));
            }
            final LevelS2C level = serverLevel;
            if (level != null && ServerPlayNetworking.canSend(handler.player, LevelS2C.TYPE)) {
                ServerPlayNetworking.send(handler.player, level);
            }
        });
        // A block the player breaks is no longer ours to clear, and leaving it recorded
        // grows the set for the whole session and can carry a position into the next world.
        net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents.AFTER.register(
            (world, player, pos, state, entity) -> {
                if (world.dimension().equals(DOOM_LEVEL_DIM)) {
                    placedBlocks.remove(pos);
                }
            });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            final UUID gone = handler.player.getUUID();
            // The rate windows are keyed by player and would otherwise keep an entry for
            // every account that has ever connected.
            dmgWindow.remove(gone);
            scavWindow.remove(gone);
            HEAL_WINDOW.remove(gone); // the pre-existing window leaked the same way
            if (MarineRoster.SERVER.remove(gone)) {
                broadcast(server, new FormS2C(gone, false));
            }
            if (gone.equals(serverLevelOwner)) {
                // the level's engine left with its owner
                serverLevel = null;
                serverLevelOwner = null;
                for (ServerPlayer p : PlayerLookup.all(server)) {
                    if (ServerPlayNetworking.canSend(p, LevelS2C.TYPE)) {
                        ServerPlayNetworking.send(p, new LevelS2C("", 0, 0, 0, false, gone));
                    }
                }
            }
        });

        // ---- client side ----
        ClientPlayNetworking.registerGlobalReceiver(FormS2C.TYPE, (payload, context) -> {
            if (payload.on()) {
                MarineRoster.CLIENT.add(payload.who());
            } else {
                MarineRoster.CLIENT.remove(payload.who());
            }
        });
        ClientPlayNetworking.registerGlobalReceiver(HelloS2C.TYPE, (payload, context) -> {
            final var player = context.client().player;
            if (player == null) {
                return;
            }
            final java.nio.file.Path mine = localIwad.get();
            if (mine == null) {
                // THE disclaimer: the mod ships zero id Software assets, by design
                player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                    "§6Latte Doom§r runs on this world, but you have no game data."
                    + " It is a source port, so the levels, textures and sounds come from a"
                    + " DOOM or DOOM II WAD and none of it ships with the mod."));
                player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                    "Put §eDOOM.WAD§r or §eDOOM2.WAD§r, from Steam, GOG or your own disc,"
                    + " into §econfig/latte-doom/§r and rejoin. Type §e/lattedoom§r for the"
                    + " command list."));
                return;
            }
            final String host = payload.hostIwad();
            if (!host.isEmpty() && !host.equalsIgnoreCase(mine.getFileName().toString())) {
                player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                    "§6Latte Doom§r: the host is playing §e" + host + "§r and you have §e"
                    + mine.getFileName() + "§r. Everything you see is built from your own"
                    + " copy, so maps and artwork can differ, and a map the host loads that"
                    + " yours does not contain will not appear for you. Use the same WAD to"
                    + " see the same game."));
            }
        });
        ClientPlayNetworking.registerGlobalReceiver(LevelS2C.TYPE, (payload, context) -> {
            if (payload.up()) {
                com.blackwithersteve.lattedoom.render.LatteWorld.setRemoteLevel(
                    payload.map(), payload.ox(), payload.oy(), payload.oz(), payload.owner());
            } else {
                // The OWNER ignores their own level-down ENTIRELY: their local teardown
                // already handled state, and this echo lands ticks later — running
                // clearRemoteLevel here cleared warpedIn out from under an in-flight
                // recovery or delivery (one of the level-finish ejection races).
                final var self = context.client().player;
                if (self != null && payload.owner().equals(self.getUUID())) {
                    return;
                }
                com.blackwithersteve.lattedoom.render.LatteWorld.clearRemoteLevel();
                // GUESTS standing in the shared level's dimension come back to the
                // overworld.
                com.blackwithersteve.lattedoom.render.LatteWorld.leaveLevelDim(context.client());
            }
        });
        ClientPlayNetworking.registerGlobalReceiver(SnapS2C.TYPE, (payload, context) ->
            com.blackwithersteve.lattedoom.render.LatteWorld.acceptRemoteSnap(payload.snap()));
        ClientPlayNetworking.registerGlobalReceiver(PresenceS2C.TYPE, (payload, context) ->
            com.blackwithersteve.lattedoom.render.LatteWorld.acceptPresence(payload.who(),
                payload.x(), payload.y(), payload.z(), payload.angleDeg(),
                payload.buttons(), payload.slot(), payload.healthMc(), payload.crossings()));
        ClientPlayNetworking.registerGlobalReceiver(SoundsS2C.TYPE, (payload, context) -> {
            for (int[] e : payload.events()) {
                com.blackwithersteve.lattedoom.render.DoomSfx.play(e[0], e[3] != 0, e[1], e[2]);
            }
        });
        ClientPlayNetworking.registerGlobalReceiver(DoomHitS2C.TYPE, (payload, context) -> {
            final var host = com.blackwithersteve.lattedoom.LatteDoomClient.host();
            if (host != null) {
                host.requestThingDamage(payload.mobjId(), payload.damageHp(), payload.attacker());
            }
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            MarineRoster.CLIENT.clear();
            com.blackwithersteve.lattedoom.render.LatteWorld.clearRemoteLevel();
            com.blackwithersteve.lattedoom.LatteDoomClient.resetOnDisconnect();
        });
        // rejoin with the form still on (the client flag outlives the connection):
        // re-announce so the fresh server roster matches what this player sees
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) ->
            sendMarineForm(com.blackwithersteve.lattedoom.render.LatteWorld.marineForm()));
    }

    /** The local player's toggle, announced to the server (no-op while not connected). */
    public static void sendMarineForm(boolean on) {
        if (Minecraft.getInstance().getConnection() != null) {
            ClientPlayNetworking.send(new FormC2S(on));
        }
    }

    /** My engine raised a level: share name + origin so every client raises it too. */
    public static void sendLevelUp(String map, double ox, double oy, double oz) {
        if (Minecraft.getInstance().getConnection() != null) {
            ClientPlayNetworking.send(new LevelC2S(map, ox, oy, oz, true));
        }
    }

    /** My engine left its level (warp reboot, quit): take the shared one down. */
    public static void sendLevelDown() {
        if (Minecraft.getInstance().getConnection() != null) {
            ClientPlayNetworking.send(new LevelC2S("", 0, 0, 0, false));
        }
    }

    /** A DOOM-weapon hit on a Minecraft entity, reported to the server for application. */
    public static void sendHit(int entityId, int damageHp) {
        if (Minecraft.getInstance().getConnection() != null) {
            ClientPlayNetworking.send(new HitC2S(entityId, damageHp));
        }
    }

    /** A MINECRAFT hit on a DOOM thing (fist/sword: projectileId -1; arrows: their id). */
    public static void sendDoomHit(int mobjId, int damageHp, int projectileId) {
        if (Minecraft.getInstance().getConnection() != null) {
            ClientPlayNetworking.send(new DoomHitC2S(mobjId, damageHp, projectileId));
        }
    }

    /** The engine billed a Minecraft player: route through the SERVER (guest-safe). */
    public static void sendPlayerDamage(UUID target, int dmgHp, int healHp,
                                        double kx, double kz, boolean blocked) {
        if (Minecraft.getInstance().getConnection() != null) {
            ClientPlayNetworking.send(
                new PlayerDamageC2S(target, dmgHp, healHp, kx, kz, blocked));
        }
    }

    /** Steve scavenged a DOOM item: ask the server for the configured MC conversion. */
    public static void sendScavenge(int healHp, int foodPts, String itemId, int count) {
        if (Minecraft.getInstance().getConnection() != null) {
            ClientPlayNetworking.send(new ScavengeC2S(healHp, foodPts,
                itemId == null ? "" : itemId, count));
        }
    }

    /** Place the held block at this level-dim cell (B2 — cell chosen by the doom-mesh ray). */
    public static void sendPlaceBlock(net.minecraft.core.BlockPos pos, boolean offHand) {
        if (Minecraft.getInstance().getConnection() != null) {
            ClientPlayNetworking.send(new PlaceBlockC2S(pos, offHand));
        }
    }

    /** Ask the server to teleport us — replaces the old /tp chat command (no op needed,
     * no chat spam). Returns true if the request went out. */
    public static boolean sendTeleport(double x, double y, double z) {
        if (Minecraft.getInstance().getConnection() == null) {
            return false;
        }
        ClientPlayNetworking.send(new TeleportC2S(x, y, z));
        return true;
    }

    /** Ask the server to move us into the private level dimension at this position. */
    public static void sendEnterLevelDim(double x, double y, double z) {
        if (Minecraft.getInstance().getConnection() != null) {
            ClientPlayNetworking.send(new EnterLevelDimC2S(x, y, z));
        }
    }

    /** Ask the server to bring us back to the overworld at this position. */
    public static void sendLeaveLevelDim(double x, double y, double z) {
        if (Minecraft.getInstance().getConnection() != null) {
            ClientPlayNetworking.send(new LeaveLevelDimC2S(x, y, z));
        }
    }

    /** A spectator's presence, once per client tick while inside the shared level. */
    public static void sendPresence(double x, double y, double z, double angleDeg,
                                    int buttons, int slot, int healthMc, int[][] crossings) {
        if (Minecraft.getInstance().getConnection() != null) {
            ClientPlayNetworking.send(new PresenceC2S(x, y, z, angleDeg, buttons, slot,
                healthMc, crossings));
        }
    }

    /** The owner's ~20Hz world feed. Skipped alone in single-player (nobody listening). */
    public static void sendSnap(com.blackwithersteve.lattedoom.engine.WorldSnapshot s) {
        final Minecraft mc = Minecraft.getInstance();
        if (mc.getConnection() == null) {
            return;
        }
        final var sp = mc.getSingleplayerServer();
        if (sp != null && sp.getPlayerCount() <= 1) {
            return;
        }
        ClientPlayNetworking.send(new SnapC2S(s));
    }

    /** The owner's sound events this tick. Same solo-skip as the world feed. */
    public static void sendSounds(java.util.List<int[]> events) {
        final Minecraft mc = Minecraft.getInstance();
        if (events.isEmpty() || mc.getConnection() == null) {
            return;
        }
        final var sp = mc.getSingleplayerServer();
        if (sp != null && sp.getPlayerCount() <= 1) {
            return;
        }
        ClientPlayNetworking.send(new SoundsC2S(events.toArray(new int[0][])));
    }

    private static void broadcast(net.minecraft.server.MinecraftServer server, FormS2C msg) {
        for (ServerPlayer p : PlayerLookup.all(server)) {
            sendTo(p, msg);
        }
    }

    /** Vanilla clients on the LAN don't speak our channel — skip them, never error. */
    private static void sendTo(ServerPlayer p, FormS2C msg) {
        if (ServerPlayNetworking.canSend(p, FormS2C.TYPE)) {
            ServerPlayNetworking.send(p, msg);
        }
    }

    private LatteNet() {}
}
