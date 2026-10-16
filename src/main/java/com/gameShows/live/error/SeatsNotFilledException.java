package com.gameShows.live.error;

import org.springframework.http.HttpStatus;

public class SeatsNotFilledException extends LiveGameException {
    public SeatsNotFilledException(long openSeats) {
        super(HttpStatus.CONFLICT, openSeats == 1
                ? "Waiting for 1 more player to join - or make that seat an AI."
                : "Waiting for " + openSeats + " more players to join - or make those seats AI.");
    }
}
