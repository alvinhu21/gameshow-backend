package com.gameShows.live.error;

import org.springframework.http.HttpStatus;

public class SessionNotFoundException extends LiveGameException {
    public SessionNotFoundException(String code) {
        super(HttpStatus.NOT_FOUND, "No game session found with code " + code);
    }
}
