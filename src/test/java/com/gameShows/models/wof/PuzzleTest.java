package com.gameShows.models.wof;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PuzzleTest {

    @Test
    void shortTextIsRightPaddedToExactlyBoardLength() {
        Puzzle puzzle = new Puzzle();

        puzzle.setPuzzle("HAWAII BEACH");

        assertThat(puzzle.getPuzzle()).hasSize(Puzzle.PUZZLE_LENGTH);
        assertThat(puzzle.getPuzzle()).startsWith("HAWAII BEACH");
        assertThat(puzzle.getPuzzle().trim()).isEqualTo("HAWAII BEACH");
    }

    @Test
    void textAlreadyAtBoardLengthIsUnchanged() {
        Puzzle puzzle = new Puzzle();
        String exact = "A".repeat(Puzzle.PUZZLE_LENGTH);

        puzzle.setPuzzle(exact);

        assertThat(puzzle.getPuzzle()).isEqualTo(exact);
    }

    @Test
    void textLongerThanBoardLengthIsRejected() {
        Puzzle puzzle = new Puzzle();
        String tooLong = "A".repeat(Puzzle.PUZZLE_LENGTH + 1);

        assertThatThrownBy(() -> puzzle.setPuzzle(tooLong)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nullTextIsPaddedToAllSpaces() {
        Puzzle puzzle = new Puzzle();

        puzzle.setPuzzle(null);

        assertThat(puzzle.getPuzzle()).hasSize(Puzzle.PUZZLE_LENGTH);
        assertThat(puzzle.getPuzzle()).isBlank();
    }
}
