package com.gameShows.live.error;

import org.springframework.http.HttpStatus;

/** The host sent a seat list the lobby can't accept (wrong size, a bad name, a joined player moved or dropped). */
public class InvalidSeatsException extends LiveGameException {
    public InvalidSeatsException(String message) {
        super(HttpStatus.BAD_REQUEST, message);
    }
}
