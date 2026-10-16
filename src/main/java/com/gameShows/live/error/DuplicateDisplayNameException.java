package com.gameShows.live.error;

import org.springframework.http.HttpStatus;

public class DuplicateDisplayNameException extends LiveGameException {
    public DuplicateDisplayNameException(String displayName) {
        super(HttpStatus.BAD_REQUEST, "\"" + displayName + "\" is already taken in this session.");
    }
}
