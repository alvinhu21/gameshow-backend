package com.gameShows.live.rest;

import com.gameShows.live.session.GameType;

public record CreateSessionRequest(GameType gameType, String hostDisplayName) {
}
