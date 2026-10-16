package com.gameShows.live.ws;

import com.gameShows.live.session.AiCapableEngine;
import com.gameShows.live.session.AiMove;
import com.gameShows.live.session.CompletableGameEngine;
import com.gameShows.live.session.CompletedGame;
import com.gameShows.live.session.GameCompletedEvent;
import com.gameShows.live.session.GameSession;
import com.gameShows.live.session.LiveActionOutcome;
import com.gameShows.live.session.SessionRegistry;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * Central place where every gameplay action is applied: look up the session, hold its lock only
 * while mutating the engine, then broadcast the resulting outcome once the lock is released.
 * Also the only place that schedules delayed follow-up actions (answer-window timeouts, the
 * Wheel of Fortune round-over pause, AI moves) so every scheduled callback goes through the same
 * lock-then-broadcast pipeline as a live player action - and the only place that notices a game
 * has finished, so it's announced (as a {@link GameCompletedEvent}) exactly once.
 */
@Component
public class LiveGameOrchestrator {

    private final SessionRegistry registry;
    private final SimpMessagingTemplate messagingTemplate;
    private final TaskScheduler taskScheduler;
    private final ApplicationEventPublisher eventPublisher;

    public LiveGameOrchestrator(SessionRegistry registry, SimpMessagingTemplate messagingTemplate,
            TaskScheduler taskScheduler, ApplicationEventPublisher eventPublisher) {
        this.registry = registry;
        this.messagingTemplate = messagingTemplate;
        this.taskScheduler = taskScheduler;
        this.eventPublisher = eventPublisher;
    }

    public void act(String code, Function<GameSession, LiveActionOutcome> action) {
        GameSession session = registry.get(code);
        LiveActionOutcome outcome;
        List<AiMove> aiMoves = List.of();
        Optional<CompletedGame> completedGame = Optional.empty();
        session.getLock().lock();
        try {
            session.touch();
            outcome = action.apply(session);
            if (session.getEngine() instanceof AiCapableEngine aiEngine) {
                aiMoves = aiEngine.pendingAiMoves();
            }
            if (session.getEngine() instanceof CompletableGameEngine completable) {
                completedGame = completable.claimCompletedGame();
            }
        } finally {
            session.getLock().unlock();
        }
        if (outcome != null) {
            broadcast(code, outcome);
        }
        // Listeners (e.g. saving the results) run on this thread, so this waits until the lock is
        // released and the players already have the final state - recording never holds up play.
        completedGame.ifPresent(game -> eventPublisher.publishEvent(new GameCompletedEvent(session.getCode(), game)));
        for (AiMove move : aiMoves) {
            scheduleDelayed(code, move.delay(), move.invoke());
        }
    }

    /** Registers a delayed follow-up that runs through the same lock+broadcast pipeline as {@link #act}. */
    public void scheduleDelayed(String code, Duration delay, Function<GameSession, LiveActionOutcome> action) {
        taskScheduler.schedule(() -> {
            try {
                act(code, action);
            } catch (RuntimeException ignored) {
                // Session may have ended/been evicted by the time this fires - nothing to do.
            }
        }, Instant.now().plus(delay));
    }

    private void broadcast(String code, LiveActionOutcome outcome) {
        messagingTemplate.convertAndSend("/topic/session/" + code + "/state", outcome.snapshot());
        if (outcome.announceText() != null) {
            messagingTemplate.convertAndSend("/topic/session/" + code + "/announce",
                    Map.of("type", "ANNOUNCE", "text", outcome.announceText()));
        }
        outcome.privateMessages().forEach(
                (playerId, payload) -> messagingTemplate.convertAndSendToUser(playerId, "/queue/live/private", payload));
    }
}
