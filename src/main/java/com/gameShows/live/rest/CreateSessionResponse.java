package com.gameShows.live.rest;

import com.gameShows.live.session.GameSession;
import com.gameShows.live.session.GameType;
import com.gameShows.live.session.SessionStatus;

public record CreateSessionResponse(String code, GameType gameType, String hostPlayerId, SessionStatus status) {

    public static CreateSessionResponse from(GameSession session) {
        return new CreateSessionResponse(session.getCode(), session.getGameType(), session.getHostPlayerId(),
                session.getStatus());
    }
}
