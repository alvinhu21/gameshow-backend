package com.gameShows.live.error;

import org.springframework.http.HttpStatus;

public class InvalidActionException extends LiveGameException {
    public InvalidActionException(String message) {
        super(HttpStatus.CONFLICT, message);
    }
}
