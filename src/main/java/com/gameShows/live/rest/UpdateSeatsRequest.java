package com.gameShows.live.rest;

import com.gameShows.live.session.Seat;

import java.util.List;

/**
 * The host's whole seat list, in order. A taken seat is sent back with its {@code playerId} (its
 * name is ignored - it's the occupant's); an open or AI seat is sent without one.
 */
public record UpdateSeatsRequest(String hostPlayerId, List<SeatRequest> seats) {

    public record SeatRequest(String name, Boolean ai, String playerId) {
    }

    /** Null when no list was sent, so the session rejects it like any other bad size. */
    public List<Seat> toSeats() {
        if (seats == null) {
            return null;
        }
        return seats.stream()
                .map(seat -> seat == null
                        ? null
                        : new Seat(seat.name(), Boolean.TRUE.equals(seat.ai()),
                                seat.playerId() == null || seat.playerId().isBlank() ? null : seat.playerId()))
                .toList();
    }
}
