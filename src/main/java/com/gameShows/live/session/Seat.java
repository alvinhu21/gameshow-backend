package com.gameShows.live.session;

/**
 * One place at the table besides the host's, as the host sets it up in the lobby: an AI opponent,
 * or a seat for a person. A person's seat stays open until someone joins; joiners take the open
 * seats first come, first served, and the joiner's own name replaces whatever the host called it.
 *
 * @param name     what the seat is called - for a taken seat, the name of the person in it; may be
 *                 blank for an open seat the host didn't label
 * @param ai       whether an AI plays this seat
 * @param playerId who plays this seat: the person who joined it, or the AI once the game has
 *                 started - null while a person's seat is open, and for an AI seat before the start
 */
public record Seat(String name, boolean ai, String playerId) {

    public Seat {
        name = name == null ? "" : name.strip();
    }

    public static Seat open(String label) {
        return new Seat(label, false, null);
    }

    /** A person's seat that nobody has joined yet. */
    public boolean isOpen() {
        return !ai && playerId == null;
    }
}
