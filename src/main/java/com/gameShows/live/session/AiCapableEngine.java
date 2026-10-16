package com.gameShows.live.session;

import java.util.List;

/**
 * Implemented by engines that can include AI-controlled players. Called once after every action's
 * broadcast (see {@code LiveGameOrchestrator.act}), so it must reflect the CURRENT state at call
 * time - not schedule based on stale assumptions.
 */
public interface AiCapableEngine {

    List<AiMove> pendingAiMoves();
}
