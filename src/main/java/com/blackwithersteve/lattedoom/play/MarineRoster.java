package com.blackwithersteve.lattedoom.play;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Who is currently a DOOM marine — the first synced fact of the server-sync milestone.
 * SERVER: authoritative roster (fed by LatteNet C2S, read by server-thread mixins like
 * the item-pickup block). CLIENT: this client's mirror of the roster (fed by S2C, read
 * by the avatar renderer so OTHER players show as the WAD's PLAY marine).
 */
public final class MarineRoster {

    public static final Set<UUID> SERVER = ConcurrentHashMap.newKeySet();
    public static final Set<UUID> CLIENT = ConcurrentHashMap.newKeySet();

    private MarineRoster() {}
}
