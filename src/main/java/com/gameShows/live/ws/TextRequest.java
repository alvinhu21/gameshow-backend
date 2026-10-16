package com.gameShows.live.ws;

/** Generic free-text payload - used for puzzle-solve attempts and Jeopardy answers alike. */
public record TextRequest(String value) {
}
