package com.gameShows.live.session;

import java.time.Duration;
import java.util.function.Function;

/**
 * A single AI-driven action waiting to be scheduled: after {@code delay}, {@code invoke} runs
 * through the same lock-then-broadcast pipeline as a real player action (see
 * {@code LiveGameOrchestrator}). Engines don't need to guard against staleness themselves - every
 * action method already validates whose turn/phase it is and throws otherwise, and the orchestrator
 * silently drops a thrown exception as a no-op, so a move that's no longer valid by the time it
 * fires just quietly does nothing.
 */
public record AiMove(Duration delay, Function<GameSession, LiveActionOutcome> invoke) {
}
