package com.gameShows.live.rest;

import com.gameShows.live.session.GameType;
import com.gameShows.live.session.PlayerView;

import java.util.List;

public record JoinSessionResponse(String playerId, String code, GameType gameType, List<PlayerView> players) {
}
