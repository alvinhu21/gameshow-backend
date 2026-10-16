package com.gameShows.api;

import com.gameShows.models.jeopardy.JeopardyGameResult;
import com.gameShows.models.jeopardy.JeopardyPlayerResult;

import java.time.Instant;

/**
 * One human contestant's finish in one recorded Jeopardy game, with that game's details inlined.
 *
 * @param place           1 is first; tied scores share a place.
 * @param contestantCount everyone in the game, AI opponents included - what place is "out of".
 */
public record PlayerResultView(
        long resultId,
        String displayName,
        int finalScore,
        int place,
        boolean tied,
        long gameId,
        String sessionCode,
        Instant completedAt,
        int roundCount,
        int contestantCount) {

    public static PlayerResultView from(JeopardyPlayerResult result) {
        JeopardyGameResult game = result.getGame();
        return new PlayerResultView(result.getId(), result.getDisplayName(), result.getFinalScore(), result.getPlace(),
                result.isTied(), game.getId(), game.getSessionCode(), game.getCompletedAt(), game.getRoundCount(),
                game.getContestantCount());
    }
}
