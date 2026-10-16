package com.gameShows.live.rest;

import com.gameShows.live.session.AiDifficulty;

/**
 * @param gameDurationMinutes Wheel of Fortune only - how long the game clock runs before Round 4+
 *                            starts routing into the Final Spin instead of another normal round.
 * @param jeopardyRounds      Jeopardy only - how many regular rounds to play before Final Jeopardy
 *                            (2-4).
 * @param clueBaseIncrement   Jeopardy only - the dollar step between rows in round 1; round N uses
 *                            this x N.
 * @param aiDifficulty        How strong the computer opponents are, for either game type.
 * @param allowTies           Jeopardy only - whether the game may end with two or more contestants
 *                            sharing the lead. When false they play tiebreaker clues until one of
 *                            them gets one right.
 *
 * <p>Every field is optional; null falls back to the engine's default.
 */
public record StartSessionRequest(String hostPlayerId, Integer gameDurationMinutes, Integer jeopardyRounds,
        Integer clueBaseIncrement, AiDifficulty aiDifficulty, Boolean allowTies) {
}
