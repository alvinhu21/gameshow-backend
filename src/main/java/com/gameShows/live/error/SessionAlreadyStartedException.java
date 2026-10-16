package com.gameShows.live.error;

import org.springframework.http.HttpStatus;

public class SessionAlreadyStartedException extends LiveGameException {
    public SessionAlreadyStartedException() {
        super(HttpStatus.CONFLICT, "This game session has already started.");
    }
}
