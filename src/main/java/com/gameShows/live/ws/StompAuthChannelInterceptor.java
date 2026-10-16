package com.gameShows.live.ws;

import com.gameShows.live.session.GameSession;
import com.gameShows.live.session.SessionRegistry;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Every STOMP CONNECT must carry a {@code sessionCode}/{@code playerId} native header pair (there's
 * no other auth in this app). We validate them against the in-memory session registry and stamp the
 * accepted connection with a {@link StompPrincipal} so {@code /user/queue/...} addressing and
 * {@code Principal}-typed {@code @MessageMapping} parameters work for the rest of the connection.
 */
@Component
public class StompAuthChannelInterceptor implements ChannelInterceptor {

    private final SessionRegistry registry;
    private final StompSessionRegistry stompSessionRegistry;

    public StompAuthChannelInterceptor(SessionRegistry registry, StompSessionRegistry stompSessionRegistry) {
        this.registry = registry;
        this.stompSessionRegistry = stompSessionRegistry;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor != null && StompCommand.CONNECT.equals(accessor.getCommand())) {
            String sessionCode = firstHeader(accessor, "sessionCode");
            String playerId = firstHeader(accessor, "playerId");
            if (sessionCode == null || playerId == null) {
                throw new IllegalArgumentException("Missing sessionCode/playerId connect headers.");
            }
            GameSession session = registry.get(sessionCode);
            if (session.findPlayer(playerId) == null) {
                throw new IllegalArgumentException("Unknown player for session " + sessionCode + ".");
            }
            accessor.setUser(new StompPrincipal(playerId));
            stompSessionRegistry.put(accessor.getSessionId(), sessionCode, playerId);
        }
        return message;
    }

    private String firstHeader(StompHeaderAccessor accessor, String name) {
        List<String> values = accessor.getNativeHeader(name);
        return (values == null || values.isEmpty()) ? null : values.get(0);
    }
}
