package com.gameShows.live.error;

import org.springframework.http.HttpStatus;

public class SessionFullException extends LiveGameException {
    public SessionFullException() {
        super(HttpStatus.CONFLICT, "This game has no open seats left - ask the host to add one.");
    }
}
