package com.gameShows.live.ws;

import com.gameShows.live.error.SessionNotFoundException;
import com.gameShows.live.session.GameSession;
import com.gameShows.live.session.PlayerView;
import com.gameShows.live.session.SeatView;
import com.gameShows.live.session.SessionPlayer;
import com.gameShows.live.session.SessionRegistry;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.util.List;

@Component
public class WebSocketEventListener {

    private final SessionRegistry registry;
    private final StompSessionRegistry stompSessionRegistry;
    private final SimpMessagingTemplate messagingTemplate;

    public WebSocketEventListener(SessionRegistry registry, StompSessionRegistry stompSessionRegistry,
            SimpMessagingTemplate messagingTemplate) {
        this.registry = registry;
        this.stompSessionRegistry = stompSessionRegistry;
        this.messagingTemplate = messagingTemplate;
    }

    /** Fired once the CONNECTED frame goes back to the client - flips the player back to
     *  "connected" after any reconnect (a dropped phone signal, a refresh, a tab navigation). */
    @EventListener
    public void onConnected(SessionConnectedEvent event) {
        setConnectedAndBroadcast(event.getMessage(), true);
    }

    @EventListener
    public void onDisconnect(SessionDisconnectEvent event) {
        setConnectedAndBroadcast(event.getMessage(), false);
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        stompSessionRegistry.remove(accessor.getSessionId());
    }

    private void setConnectedAndBroadcast(Message<?> message, boolean connected) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(message);
        StompSessionRegistry.PlayerRef ref = stompSessionRegistry.get(accessor.getSessionId());
        if (ref == null) {
            return;
        }
        try {
            GameSession session = registry.get(ref.sessionCode());
            SessionPlayer player = session.findPlayer(ref.playerId());
            if (player == null) {
                return;
            }
            List<PlayerView> players;
            List<SeatView> seats;
            session.getLock().lock();
            try {
                player.setConnected(connected);
                players = session.playersInSeatOrder().stream().map(PlayerView::from).toList();
                seats = session.seatViews();
            } finally {
                session.getLock().unlock();
            }
            messagingTemplate.convertAndSend("/topic/session/" + ref.sessionCode() + "/players", players);
            messagingTemplate.convertAndSend("/topic/session/" + ref.sessionCode() + "/seats", seats);
        } catch (SessionNotFoundException ignored) {
            // Session already ended/evicted - nothing to update.
        }
    }
}
