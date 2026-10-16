package com.gameShows.live.jeopardy;

public record FinalResultEntry(
        String playerId,
        String displayName,
        int wager,
        String answerText,
        boolean correct,
        int scoreDelta,
        int finalScore) {
}
