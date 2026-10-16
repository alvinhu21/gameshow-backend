package com.gameShows.live.session;

/** A seat as the lobby shows it. `connected` is only ever true for a seat someone is playing. */
public record SeatView(String name, boolean ai, String playerId, boolean connected) {

    public static SeatView from(Seat seat, SessionPlayer occupant) {
        return new SeatView(seat.name(), seat.ai(), seat.playerId(), occupant != null && occupant.isConnected());
    }
}
