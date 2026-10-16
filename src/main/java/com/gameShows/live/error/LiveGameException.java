package com.gameShows.live.error;

import org.springframework.http.HttpStatus;

public abstract class LiveGameException extends RuntimeException {

    private final HttpStatus status;

    protected LiveGameException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return getClass().getSimpleName().replace("Exception", "");
    }
}
