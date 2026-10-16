package com.gameShows.services;

import com.gameShows.live.session.GameCompletedEvent;
import com.gameShows.live.session.GameType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Records each finished game. Runs on the thread that finished it, after the players already have
 * the final state, so a failure here is logged rather than thrown - it must never surface as a
 * gameplay error.
 */
@Component
public class GameResultsListener {

    private static final Logger log = LoggerFactory.getLogger(GameResultsListener.class);

    private final JeopardyResultsService jeopardyResultsService;

    public GameResultsListener(JeopardyResultsService jeopardyResultsService) {
        this.jeopardyResultsService = jeopardyResultsService;
    }

    @EventListener
    public void onGameCompleted(GameCompletedEvent event) {
        if (event.game().gameType() != GameType.JEOPARDY) {
            return;
        }
        try {
            jeopardyResultsService.recordGame(event.sessionCode(), event.game());
        } catch (RuntimeException ex) {
            log.error("Failed to record Jeopardy result: sessionCode={} contestants={}", event.sessionCode(),
                    event.game().standings().size(), ex);
        }
    }
}
