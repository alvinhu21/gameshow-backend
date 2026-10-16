package com.gameShows.live.error;

import org.springframework.http.HttpStatus;

public class NotHostException extends LiveGameException {
    public NotHostException() {
        super(HttpStatus.FORBIDDEN, "Only the host can perform this action.");
    }
}
