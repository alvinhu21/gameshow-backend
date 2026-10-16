package com.gameShows.live.rest;

import com.gameShows.live.session.GameSession;
import com.gameShows.live.session.GameType;
import com.gameShows.live.session.PlayerView;
import com.gameShows.live.session.SeatView;
import com.gameShows.live.session.SessionStatus;

import java.util.List;

/** Build it while holding the session's lock - it reads the players and seats together. */
public record SessionSummaryResponse(String code, GameType gameType, SessionStatus status, List<PlayerView> players,
        List<SeatView> seats) {

    public static SessionSummaryResponse from(GameSession session) {
        return new SessionSummaryResponse(
                session.getCode(),
                session.getGameType(),
                session.getStatus(),
                session.playersInSeatOrder().stream().map(PlayerView::from).toList(),
                session.seatViews());
    }
}
