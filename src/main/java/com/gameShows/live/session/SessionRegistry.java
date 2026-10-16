package com.gameShows.live.session;

import com.gameShows.live.error.SessionNotFoundException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class SessionRegistry {

    private static final String CODE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int CODE_LENGTH = 6;

    private final Map<String, GameSession> sessions = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();

    public GameSession create(GameType gameType, String hostDisplayName) {
        String code = generateUniqueCode();
        String hostPlayerId = UUID.randomUUID().toString();
        GameSession session = new GameSession(code, gameType, hostPlayerId);
        session.addPlayer(new SessionPlayer(hostPlayerId, hostDisplayName, true));
        sessions.put(code, session);
        return session;
    }

    public GameSession get(String code) {
        GameSession session = sessions.get(normalize(code));
        if (session == null) {
            throw new SessionNotFoundException(code);
        }
        return session;
    }

    private String normalize(String code) {
        return code == null ? "" : code.trim().toUpperCase();
    }

    private String generateUniqueCode() {
        String code;
        do {
            StringBuilder sb = new StringBuilder(CODE_LENGTH);
            for (int i = 0; i < CODE_LENGTH; i++) {
                sb.append(CODE_CHARS.charAt(random.nextInt(CODE_CHARS.length())));
            }
            code = sb.toString();
        } while (sessions.containsKey(code));
        return code;
    }

    @Scheduled(fixedDelay = 60_000)
    public void evictStaleSessions() {
        Instant now = Instant.now();
        sessions.entrySet().removeIf(entry -> entry.getValue().isEligibleForEviction(now));
    }
}
