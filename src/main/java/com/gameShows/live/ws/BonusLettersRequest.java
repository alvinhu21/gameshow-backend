package com.gameShows.live.ws;

import java.util.List;

/** The bonus-round contestant's chosen 3 consonants + 1 vowel (RSTLNE is pre-revealed). */
public record BonusLettersRequest(List<Character> consonants, String vowel) {
}
