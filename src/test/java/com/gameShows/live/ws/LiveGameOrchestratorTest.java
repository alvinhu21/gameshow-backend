package com.gameShows.live.ws;

import com.gameShows.live.session.CompletableGameEngine;
import com.gameShows.live.session.CompletedGame;
import com.gameShows.live.session.GameCompletedEvent;
import com.gameShows.live.session.GameSession;
import com.gameShows.live.session.GameType;
import com.gameShows.live.session.LiveActionOutcome;
import com.gameShows.live.session.LiveGameEngine;
import com.gameShows.live.session.SessionRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.TaskScheduler;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class LiveGameOrchestratorTest {

    /** An engine whose game is over as soon as {@link #finish()} is called - once. */
    private static final class FinishingEngine implements LiveGameEngine, CompletableGameEngine {
        private boolean over;
        private boolean claimed;

        void finish() {
            over = true;
        }

        @Override
        public Object toSnapshot(String forPlayerId) {
            return "snapshot";
        }

        @Override
        public Optional<CompletedGame> claimCompletedGame() {
            if (!over || claimed) {
                return Optional.empty();
            }
            claimed = true;
            return Optional.of(new CompletedGame(GameType.JEOPARDY, 2, Instant.EPOCH, List.of()));
        }
    }

    private SimpMessagingTemplate messagingTemplate;
    private ApplicationEventPublisher eventPublisher;
    private LiveGameOrchestrator orchestrator;
    private GameSession session;
    private FinishingEngine engine;

    @BeforeEach
    void setUp() {
        SessionRegistry registry = new SessionRegistry();
        messagingTemplate = mock(SimpMessagingTemplate.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        orchestrator = new LiveGameOrchestrator(registry, messagingTemplate, mock(TaskScheduler.class),
                eventPublisher);
        session = registry.create(GameType.JEOPARDY, "Host");
        engine = new FinishingEngine();
        session.setEngine(engine);
    }

    @Test
    void announcesAFinishedGameOnceAfterItsFinalStateHasGoneOut() {
        orchestrator.act(session.getCode(), s -> {
            engine.finish();
            return LiveActionOutcome.of("final state");
        });

        InOrder order = inOrder(messagingTemplate, eventPublisher);
        order.verify(messagingTemplate).convertAndSend("/topic/session/" + session.getCode() + "/state",
                (Object) "final state");
        order.verify(eventPublisher).publishEvent(any(GameCompletedEvent.class));

        orchestrator.act(session.getCode(), s -> LiveActionOutcome.of("still over"));
        verify(eventPublisher).publishEvent(any(GameCompletedEvent.class));
    }

    @Test
    void theAnnouncementCarriesTheSessionCode() {
        orchestrator.act(session.getCode().toLowerCase(), s -> {
            engine.finish();
            return null;
        });

        verify(eventPublisher).publishEvent(
                new GameCompletedEvent(session.getCode(), new CompletedGame(GameType.JEOPARDY, 2, Instant.EPOCH, List.of())));
    }

    @Test
    void saysNothingWhileTheGameIsStillGoing() {
        orchestrator.act(session.getCode(), s -> LiveActionOutcome.of("mid-game"));

        verify(eventPublisher, never()).publishEvent(any(Object.class));
        verify(messagingTemplate).convertAndSend(anyString(), (Object) any());
        assertThat(engine.claimed).isFalse();
    }
}
