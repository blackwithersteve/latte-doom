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
 * Payload definitions and handlers for both sides. The layer carries the roster of
 * transformed players, the level a client has raised and its origin, world snapshots and
 * sound events for spectators, damage in both directions, and the player actions the server
 * must apply itself, such as teleports and block placement.
 *
 * <p>Only state is transmitted; no WAD-derived content is ever sent, since every client
 * renders from its own copy of the game data. See {@code LEGAL.md}.
 */
public final class LatteNet {

    /** Client → server: this player's transformed state changed. */
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

    /** Server → joining client: the mod is active here, carrying the host's base WAD name
     * (empty when the host has none). The client compares it against its own and prints the
     * compatibility notice when they differ. */
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

    /** Client → server: this client's engine raised (up=true) or dropped (up=false) a level. */
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

    /** Server → clients: the shared level everyone should raise (or drop), and whose
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

    // ---- The live world feed: the snapshot of the client running the engine, sent about
    // twenty times a second. It carries the tic, sector heights and light levels, and the
    // full table of map objects. A spectating client feeds it into the same interpolation
    // machinery, so moving sectors, lights and monsters behave identically for everyone.
    // State only; no WAD content is ever transmitted (see LEGAL.md). ----

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

    private static com.blackwithersteve.lattedoom.engine.WorldSnapshot readSnap(RegistryFriendlyByteBuf buf) {
        final var s = com.blackwithersteve.lattedoom.engine.WorldSnapshot.forRemote();
        s.tic = buf.readVarInt();
        final int n = buf.readVarInt();
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
        final int rb = buf.readVarInt();
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
        final int m = buf.readVarInt();
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

    // Presence: a guest's body, inputs and triggers, sent upstream to the level owner's
    // engine, where they occupy one of players[1..3] and are a full participant in that
    // engine's simulation. Position, angle and buttons only; state, never content.

    /** Client → server: this client's in-level presence for the current tick. */
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

    /** Client → server: a DOOM weapon hit this Minecraft entity (mob or player) for this
     * many engine hit points. The server validates it and applies attributed damage. */
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

    /** Client → server: a Minecraft attack (fist, sword or arrow) hit this engine object.
     * A projectileId of 0 or more identifies the arrow to retire. Routed to the level
     * owner's engine. */
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

    /** Server → the level owner: apply that hit in the local engine, attributed to the sender. */
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

    /** Client → server: the engine dealt damage or healing to this Minecraft player. Sent
     * by the engine-owning client and applied by the server, which is required for LAN
     * guests: the integrated-server shortcut is null off the host and drops the damage. */
    public record PlayerDamageC2S(UUID target, int dmgHp, int healHp, double kx, double kz)
        implements CustomPacketPayload {
        public static final Type<PlayerDamageC2S> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath("lattedoom", "pdmg_c2s"));
        public static final StreamCodec<RegistryFriendlyByteBuf, PlayerDamageC2S> CODEC =
            StreamCodec.composite(UUIDUtil.STREAM_CODEC, PlayerDamageC2S::target,
                ByteBufCodecs.VAR_INT, PlayerDamageC2S::dmgHp,
                ByteBufCodecs.VAR_INT, PlayerDamageC2S::healHp,
                ByteBufCodecs.DOUBLE, PlayerDamageC2S::kx,
                ByteBufCodecs.DOUBLE, PlayerDamageC2S::kz,
                PlayerDamageC2S::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Client → server: an untransformed player picked up a DOOM item; apply the configured
     * Minecraft conversion to the sender. The server clamps every field, and a sender can
     * only ever grant itself small amounts of health, food or items. */
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

    /** Client → server: place the sender's held block at this cell of the level dimension.
     * The client picks the cell by ray-marching the drawn geometry; the server only ever
     * places what the sender is holding, and only next to the sender. */
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

    /** Client → server: teleport the sender to this world position. Applied through
     * {@code ServerPlayer.teleportTo}, which needs no operator permission, avoids the
     * "moved wrongly" rollback and keeps the chat log clean. */
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

    /** Client → server: move the sender into the private level dimension at this position,
     * an empty world in which only the raised level renders and ticks. The server performs
     * the cross-dimension teleport; if the datapack dimension is absent it falls back to a
     * same-dimension teleport, leaving the level raised in the overworld. */
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

    /** Client → server: return the sender to the overworld at this position. */
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

    /** The empty dimension levels are rendered inside, defined by the datapack at
     * data/lattedoom/dimension/doom_level.json. */
    public static final net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level>
        DOOM_LEVEL_DIM = net.minecraft.resources.ResourceKey.create(
            net.minecraft.core.registries.Registries.DIMENSION,
            Identifier.fromNamespaceAndPath("lattedoom", "doom_level"));

    /** Sound events from the world owner's engine: {sfxId, doomX, doomY, hasPos} each.
     * Ids and coordinates only: every client renders audio from its own WAD. */
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

    /** Per-player healing rate window, as the window start tick and the amount healed in
     * it. Minecraft already clamps healing to maximum health, so this exists only to stop
     * a modified client from repeatedly healing itself; genuine medikit and soulsphere
     * pickups stay well under the cap. */
    private static final java.util.Map<UUID, long[]> HEAL_WINDOW =
        new java.util.concurrent.ConcurrentHashMap<>();
    private static final int HEAL_CAP_PER_SEC = 200; // DOOM HP per second (/5 = MC half-hearts)

    /** The base WAD this side has, from the client's own scan; set at client init. */
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

        // ---- Server side: the integrated server or a lan host. ----
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
                // Last writer wins: the most recent client to raise a level owns it
                serverLevel = new LevelS2C(payload.map(), payload.ox(), payload.oy(),
                    payload.oz(), true, who.getUUID());
                serverLevelOwner = who.getUUID();
            } else {
                // Only the current owner may take the level down, so a second client's
                // engine cannot remove the level everyone is standing in
                if (!who.getUUID().equals(serverLevelOwner)) {
                    return;
                }
                serverLevel = null;
                serverLevelOwner = null;
            }
            for (ServerPlayer p : PlayerLookup.all(context.server())) {
                if (ServerPlayNetworking.canSend(p, LevelS2C.TYPE)) {
                    ServerPlayNetworking.send(p, payload.up()
                        ? serverLevel : new LevelS2C("", 0, 0, 0, false, who.getUUID()));
                }
            }
        });
        ServerPlayNetworking.registerGlobalReceiver(SnapC2S.TYPE, (payload, context) -> {
            // Only the level owner's world feed is authoritative; others are discarded
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
            // Only the world owner's sound events are rebroadcast
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
            // A guest's presence goes to exactly one place: the engine that owns the world
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
            // Only a transformed player can deal engine damage, and only within bounds
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
            living.invulnerableTime = 0; // DOOM has no invulnerability frames
            living.hurtServer((net.minecraft.server.level.ServerLevel) living.level(),
                living.damageSources().playerAttack(shooter), payload.damageHp() / 5.0f);
        });
        ServerPlayNetworking.registerGlobalReceiver(PlayerDamageC2S.TYPE, (payload, context) -> {
            if (payload.dmgHp() < 0 || payload.dmgHp() > 1000
                || payload.healHp() < 0 || payload.healHp() > 1000) {
                return;
            }
            // The level owner may apply this to any player; any client may apply it to
            // itself, which covers damage from its own suit engine
            final UUID sender = context.player().getUUID();
            if (!sender.equals(serverLevelOwner) && !sender.equals(payload.target())) {
                return;
            }
            final ServerPlayer sp = context.server().getPlayerList().getPlayer(payload.target());
            if (sp == null || sp.getAbilities().invulnerable || sp.isSpectator()) {
                return;
            }
            if (payload.dmgHp() > 0) {
                sp.invulnerableTime = 0; // DOOM has no invulnerability frames
                // Magic damage rather than generic. The engine has already applied skill
                // scaling, armour absorption and its own randomisation, so Minecraft armour
                // and shields must not reduce the result a second time; magic bypasses both.
                sp.hurtServer((net.minecraft.server.level.ServerLevel) sp.level(),
                    sp.damageSources().magic(), payload.dmgHp() / 5.0f);
                if (payload.kx() != 0 || payload.kz() != 0) {
                    sp.push(payload.kx(), 0.35, payload.kz());
                    sp.hurtMarked = true;
                }
            }
            if (payload.healHp() > 0) {
                // Rate-cap self-healing over a rolling one-second window
                final long tick = context.server().getTickCount();
                final long[] w = HEAL_WINDOW.computeIfAbsent(payload.target(),
                    k -> new long[]{tick, 0});
                if (tick - w[0] >= 20L) { // window elapsed
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
            // Item conversion: a player may only grant to themselves, and the amounts are
            // clamped to one item's worth per packet, which covers any reasonable table
            // entry. The engine has already consumed the DOOM item; this applies the
            // Minecraft side of the exchange.
            final ServerPlayer sp = context.player();
            if (sp == null || sp.isSpectator()) {
                return;
            }
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
            // Block placement, accepted only inside the level dimension, near the sender, on
            // a replaceable target with no living entity in the cell. Places exactly what the
            // sender holds, in its default state, and consumes it in survival.
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
            final var snd = state.getSoundType();
            lvl.playSound(null, pos, snd.getPlaceSound(),
                net.minecraft.sounds.SoundSource.BLOCKS,
                (snd.getVolume() + 1.0f) / 2.0f, snd.getPitch() * 0.8f);
            if (!sp.getAbilities().instabuild) {
                held.shrink(1);
            }
        });
        ServerPlayNetworking.registerGlobalReceiver(TeleportC2S.TYPE, (payload, context) -> {
            // Authoritative self-teleport: the server moves the player, so no anti-cheat
            // rollback and no /tp permission needed. Bounds-guarded against NaN/garbage.
            final double x = payload.x(), y = payload.y(), z = payload.z();
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                || Math.abs(x) > 3.0e7 || Math.abs(z) > 3.0e7 || y < -1024 || y > 2048) {
                return;
            }
            context.player().teleportTo(x, y, z);
        });
        ServerPlayNetworking.registerGlobalReceiver(EnterLevelDimC2S.TYPE, (payload, context) -> {
            final double x = payload.x(), y = payload.y(), z = payload.z();
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                || Math.abs(x) > 3.0e7 || Math.abs(z) > 3.0e7 || y < -1024 || y > 2048) {
                return;
            }
            final ServerPlayer p = context.player();
            final net.minecraft.server.level.ServerLevel target =
                context.server().getLevel(DOOM_LEVEL_DIM);
            if (target == null) {
                // Datapack dimension missing: fall back to a same-dimension teleport so
                // /load still places the player inside the level
                p.teleportTo(x, y, z);
                return;
            }
            p.teleportTo(target, x, y, z, java.util.Set.of(), p.getYRot(), p.getXRot(), false);
        });
        ServerPlayNetworking.registerGlobalReceiver(LeaveLevelDimC2S.TYPE, (payload, context) -> {
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
            // Retire a spent arrow
            if (payload.projectileId() >= 0) {
                final var proj = context.player().level().getEntity(payload.projectileId());
                if (proj instanceof net.minecraft.world.entity.projectile.arrow.AbstractArrow) {
                    proj.discard();
                }
            }
            // The engine runs on the level owner's client, so the hit is forwarded there.
            // A mobjId of -1 means the projectile hit level geometry and damages nothing.
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
            // A player who logged out inside the level dimension would rejoin into an empty
            // world, since the level is no longer raised for them, so they are returned to
            // the overworld spawn on join.
            if (handler.player.level().dimension().identifier().equals(DOOM_LEVEL_DIM.identifier())) {
                final net.minecraft.server.level.ServerLevel ow = server.overworld();
                final net.minecraft.core.BlockPos sp = ow.getRespawnData().pos();
                handler.player.teleportTo(ow, sp.getX() + 0.5, sp.getY(), sp.getZ() + 0.5,
                    java.util.Set.of(), handler.player.getYRot(), handler.player.getXRot(), false);
            }
            // The WAD handshake first; on a LAN host the server's base WAD is the host's
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
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            final UUID gone = handler.player.getUUID();
            if (MarineRoster.SERVER.remove(gone)) {
                broadcast(server, new FormS2C(gone, false));
            }
            if (gone.equals(serverLevelOwner)) {
                // The level's engine left with its owner
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
                // The mod ships no id Software assets; each client supplies its own
                player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                    "§cLatte Doom won't work here: you have no game data.§r This world runs"
                    + " Latte Doom, a source port: the levels, textures and sounds come from a"
                    + " DOOM or DOOM II WAD, which the mod does not include. You need your own"
                    + " §eDOOM.WAD§r or §eDOOM2.WAD§r (e.g. from Steam or GOG) in"
                    + " §econfig/latte-doom/§r, then rejoin."));
                return;
            }
            final String host = payload.hostIwad();
            if (!host.isEmpty() && !host.equalsIgnoreCase(mine.getFileName().toString())) {
                player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                    "§6Latte Doom§r: heads-up: the host runs §e" + host + "§r, you have §e"
                    + mine.getFileName() + "§r. Everything is composited from YOUR wad, so"
                    + " art/levels may differ: use the same WAD for an identical game."));
            }
        });
        ClientPlayNetworking.registerGlobalReceiver(LevelS2C.TYPE, (payload, context) -> {
            if (payload.up()) {
                com.blackwithersteve.lattedoom.render.LatteWorld.setRemoteLevel(
                    payload.map(), payload.ox(), payload.oy(), payload.oz(), payload.owner());
            } else {
                com.blackwithersteve.lattedoom.render.LatteWorld.clearRemoteLevel();
                // Guests standing in the shared level's dimension return to the overworld.
                // The owner ignores its own level-down here, since its engine's quit callback
                // handles that return, and a /load reboot takes the level down and straight
                // back up, which must not move the owner out and in again.
                final var self = context.client().player;
                if (self == null || !payload.owner().equals(self.getUUID())) {
                    com.blackwithersteve.lattedoom.render.LatteWorld.leaveLevelDim(context.client());
                }
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
        // The transformed flag outlives a connection, so re-announce it on rejoining and
        // keep the new server roster consistent with what this client displays.
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) ->
            sendMarineForm(com.blackwithersteve.lattedoom.render.LatteWorld.marineForm()));
    }

    /** The local player's toggle, announced to the server (no-op while not connected). */
    public static void sendMarineForm(boolean on) {
        if (Minecraft.getInstance().getConnection() != null) {
            ClientPlayNetworking.send(new FormC2S(on));
        }
    }

    /** The local engine raised a level: share its name and origin so every client raises it. */
    public static void sendLevelUp(String map, double ox, double oy, double oz) {
        if (Minecraft.getInstance().getConnection() != null) {
            ClientPlayNetworking.send(new LevelC2S(map, ox, oy, oz, true));
        }
    }

    /** The local engine left its level, through a warp reboot or a quit: take the shared
     * level down. */
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

    /** A Minecraft hit on an engine object: -1 for melee, the arrow's id for a projectile. */
    public static void sendDoomHit(int mobjId, int damageHp, int projectileId) {
        if (Minecraft.getInstance().getConnection() != null) {
            ClientPlayNetworking.send(new DoomHitC2S(mobjId, damageHp, projectileId));
        }
    }

    /** The engine damaged or healed a Minecraft player; routed through the server. */
    public static void sendPlayerDamage(UUID target, int dmgHp, int healHp,
                                        double kx, double kz) {
        if (Minecraft.getInstance().getConnection() != null) {
            ClientPlayNetworking.send(new PlayerDamageC2S(target, dmgHp, healHp, kx, kz));
        }
    }

    /** An untransformed player picked up a DOOM item: request the configured conversion. */
    public static void sendScavenge(int healHp, int foodPts, String itemId, int count) {
        if (Minecraft.getInstance().getConnection() != null) {
            ClientPlayNetworking.send(new ScavengeC2S(healHp, foodPts,
                itemId == null ? "" : itemId, count));
        }
    }

    /** Place the held block at this cell of the level dimension, chosen by the mesh ray. */
    public static void sendPlaceBlock(net.minecraft.core.BlockPos pos, boolean offHand) {
        if (Minecraft.getInstance().getConnection() != null) {
            ClientPlayNetworking.send(new PlaceBlockC2S(pos, offHand));
        }
    }

    /** Ask the server to teleport this player, which needs no operator permission and
     * writes nothing to chat. Returns true when the request was sent. */
    public static boolean sendTeleport(double x, double y, double z) {
        if (Minecraft.getInstance().getConnection() == null) {
            return false;
        }
        ClientPlayNetworking.send(new TeleportC2S(x, y, z));
        return true;
    }

    /** Ask the server to move this player into the level dimension at this position. */
    public static void sendEnterLevelDim(double x, double y, double z) {
        if (Minecraft.getInstance().getConnection() != null) {
            ClientPlayNetworking.send(new EnterLevelDimC2S(x, y, z));
        }
    }

    /** Ask the server to return this player to the overworld at this position. */
    public static void sendLeaveLevelDim(double x, double y, double z) {
        if (Minecraft.getInstance().getConnection() != null) {
            ClientPlayNetworking.send(new LeaveLevelDimC2S(x, y, z));
        }
    }

    /** A guest's presence, sent once per client tick while inside the shared level. */
    public static void sendPresence(double x, double y, double z, double angleDeg,
                                    int buttons, int slot, int healthMc, int[][] crossings) {
        if (Minecraft.getInstance().getConnection() != null) {
            ClientPlayNetworking.send(new PresenceC2S(x, y, z, angleDeg, buttons, slot,
                healthMc, crossings));
        }
    }

    /** The owner's 20 Hz world feed. Skipped in single-player, where nothing consumes it. */
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

    /** The owner's sound events for this tick. Skipped in single-player, as the feed is. */
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

    /** Vanilla clients on a LAN do not have the channel registered, so they are skipped. */
    private static void sendTo(ServerPlayer p, FormS2C msg) {
        if (ServerPlayNetworking.canSend(p, FormS2C.TYPE)) {
            ServerPlayNetworking.send(p, msg);
        }
    }

    private LatteNet() {}
}
