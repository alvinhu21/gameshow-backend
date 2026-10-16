package com.gameShows.live.session;

import java.util.Optional;

/**
 * Implemented by engines whose finished games are recorded. Asked after every action (see
 * {@code LiveGameOrchestrator.act}), while the session's lock is held.
 */
public interface CompletableGameEngine {

    /**
     * The finished game the first time this is asked once the game is over; empty before then and
     * on every later call, so a game is recorded exactly once however many actions follow its end.
     */
    Optional<CompletedGame> claimCompletedGame();
}
