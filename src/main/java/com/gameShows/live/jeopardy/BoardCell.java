package com.gameShows.live.jeopardy;

import com.gameShows.live.jeopardy.choices.ResponseChoices;
import com.gameShows.models.jeopardy.Clue;

import java.util.HashSet;
import java.util.Set;

public class BoardCell {

    private final String cellId;
    private final String category;
    private final int value;
    private final boolean dailyDouble;
    private final Clue clue;
    /** Read from the clue once with the board, rather than re-read (and a bad row re-reported) on every answer. */
    private final AnswerKey answerKey;
    /** Dealt once with the board so the options never reshuffle mid-clue; null for a typed answer. */
    private final ResponseChoices responseChoices;
    private final Set<String> lockedOutPlayerIds = new HashSet<>();

    private CellState state = CellState.HIDDEN;

    public BoardCell(String cellId, String category, int value, boolean dailyDouble, Clue clue,
            AnswerKey answerKey, ResponseChoices responseChoices) {
        this.cellId = cellId;
        this.category = category;
        this.value = value;
        this.dailyDouble = dailyDouble;
        this.clue = clue;
        this.answerKey = answerKey;
        this.responseChoices = responseChoices;
    }

    public String getCellId() {
        return cellId;
    }

    public String getCategory() {
        return category;
    }

    public int getValue() {
        return value;
    }

    public boolean isDailyDouble() {
        return dailyDouble;
    }

    public Clue getClue() {
        return clue;
    }

    public AnswerKey getAnswerKey() {
        return answerKey;
    }

    public ResponseChoices getResponseChoices() {
        return responseChoices;
    }

    public CellState getState() {
        return state;
    }

    public void setState(CellState state) {
        this.state = state;
    }

    public Set<String> getLockedOutPlayerIds() {
        return lockedOutPlayerIds;
    }
}
