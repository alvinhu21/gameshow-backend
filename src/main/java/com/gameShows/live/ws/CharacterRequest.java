package com.gameShows.live.ws;

/** Used for both guessLetter and buyVowel - {@code value} is expected to be a single letter. */
public record CharacterRequest(String value) {
}
