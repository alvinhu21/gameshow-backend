package com.gameShows.live.session;

import java.util.Map;

/**
 * Result of a single mutating engine action: the public snapshot to broadcast to everyone in the
 * session, an optional short human-readable line for screen-reader/live-region announcement, and
 * any messages that must go ONLY to specific players (e.g. a private wager acknowledgement) rather
 * than to the public topic.
 */
public record LiveActionOutcome(Object snapshot, String announceText, Map<String, Object> privateMessages) {

    public static LiveActionOutcome of(Object snapshot) {
        return new LiveActionOutcome(snapshot, null, Map.of());
    }

    public static LiveActionOutcome of(Object snapshot, String announceText) {
        return new LiveActionOutcome(snapshot, announceText, Map.of());
    }

    public static LiveActionOutcome of(Object snapshot, String announceText, Map<String, Object> privateMessages) {
        return new LiveActionOutcome(snapshot, announceText, privateMessages);
    }
}
