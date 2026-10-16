package com.gameShows.live.session;

/**
 * Common contract implemented by {@code WofEngine} and {@code JeopardyEngine} so REST/reconnect
 * code can fetch a per-player snapshot without knowing which game type a session holds.
 */
public interface LiveGameEngine {

    Object toSnapshot(String forPlayerId);
}
