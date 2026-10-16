package com.gameShows.live.ws;

import com.gameShows.live.error.LiveGameException;
import org.springframework.messaging.handler.annotation.MessageExceptionHandler;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.web.bind.annotation.ControllerAdvice;

import java.security.Principal;
import java.util.Map;

/**
 * Catches {@link LiveGameException}s thrown by any {@code @MessageMapping} handler in the live.ws
 * package and turns them into a private error frame instead of tearing down the STOMP connection.
 * Must be {@code @ControllerAdvice} (not {@code @Controller}) - Spring messaging only applies
 * {@code @MessageExceptionHandler} methods across controllers when declared this way.
 */
@ControllerAdvice
public class LiveGameMessagingExceptionHandler {

    private final SimpMessagingTemplate messagingTemplate;

    public LiveGameMessagingExceptionHandler(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    @MessageExceptionHandler(LiveGameException.class)
    public void handle(LiveGameException ex, Principal principal) {
        if (principal == null) {
            return;
        }
        messagingTemplate.convertAndSendToUser(principal.getName(), "/queue/live/errors",
                Map.of("type", "ERROR", "code", ex.getCode(), "message", ex.getMessage()));
    }
}
