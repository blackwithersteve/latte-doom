package com.blackwithersteve.lattedoom.play;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Which players are currently transformed into the DOOM marine.
 *
 * <p>{@link #SERVER} is the authoritative roster: clients announce their form over the
 * network and server-thread mixins read it, for example to suppress item pickup.
 * {@link #CLIENT} is this client's mirror of that roster, broadcast back by the server
 * and read by the avatar renderer so other players are drawn as marine sprites.
 */
public final class MarineRoster {

    public static final Set<UUID> SERVER = ConcurrentHashMap.newKeySet();
    public static final Set<UUID> CLIENT = ConcurrentHashMap.newKeySet();

    private MarineRoster() {}
}
