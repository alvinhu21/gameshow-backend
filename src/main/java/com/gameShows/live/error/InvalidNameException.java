package com.gameShows.live.error;

import org.springframework.http.HttpStatus;

public class InvalidNameException extends LiveGameException {
    public InvalidNameException(int maxLength) {
        super(HttpStatus.BAD_REQUEST, "Names must be 1-" + maxLength + " characters.");
    }
}
