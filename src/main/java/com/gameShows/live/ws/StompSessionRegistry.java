package com.gameShows.live.ws;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks which (sessionCode, playerId) each raw STOMP session id belongs to. Needed because the
 * CONNECTED-ack message Spring publishes {@code SessionConnectedEvent} with is a freshly built
 * frame that does NOT carry forward the custom session attributes {@link StompAuthChannelInterceptor}
 * stamps during CONNECT - only the STOMP session id is reliably present on every event for a given
 * connection (CONNECT, CONNECTED, and DISCONNECT alike), so it's the one stable join key available.
 */
@Component
public class StompSessionRegistry {

    public record PlayerRef(String sessionCode, String playerId) {
    }

    private final Map<String, PlayerRef> byStompSessionId = new ConcurrentHashMap<>();

    public void put(String stompSessionId, String sessionCode, String playerId) {
        byStompSessionId.put(stompSessionId, new PlayerRef(sessionCode, playerId));
    }

    public PlayerRef get(String stompSessionId) {
        return byStompSessionId.get(stompSessionId);
    }

    public void remove(String stompSessionId) {
        byStompSessionId.remove(stompSessionId);
    }
}
