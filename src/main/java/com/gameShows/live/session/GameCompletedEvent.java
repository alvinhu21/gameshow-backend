package com.gameShows.live.session;

/** Published once per finished game, after its final state has gone out to the players. */
public record GameCompletedEvent(String sessionCode, CompletedGame game) {
}
