package com.gameShows.models.wof;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

@Entity
@Table(name = "puzzles") // This defines the table name in SQLite
public class Puzzle {

    /** The real modern board is a fixed 52-cell grid (12/14/14/12) - every puzzle's text must
     *  occupy exactly that many characters, so it's right-padded with spaces up to this length. */
    public static final int PUZZLE_LENGTH = 52;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String category;

    @Column(length = PUZZLE_LENGTH)
    private String puzzle;

    // Standard Getters and Setters
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public String getPuzzle() {
        return puzzle;
    }

    /** Right-pads with spaces up to exactly {@link #PUZZLE_LENGTH} characters. Rejects text that's
     *  already too long to pad, rather than silently truncating and losing part of the puzzle. */
    public void setPuzzle(String puzzle) {
        this.puzzle = normalizeToBoardLength(puzzle);
    }

    /** Defense in depth for the (field-access) JPA lifecycle: guarantees the invariant holds for
     *  every row actually written to the database, even if something bypassed {@link #setPuzzle}. */
    @PrePersist
    @PreUpdate
    private void normalizeBeforeSave() {
        this.puzzle = normalizeToBoardLength(this.puzzle);
    }

    private static String normalizeToBoardLength(String value) {
        String text = value == null ? "" : value;
        if (text.length() > PUZZLE_LENGTH) {
            throw new IllegalArgumentException(
                    "Puzzle text is " + text.length() + " characters, which is longer than the board's "
                            + PUZZLE_LENGTH + "-character limit: \"" + text + "\"");
        }
        return text.length() == PUZZLE_LENGTH ? text : text + " ".repeat(PUZZLE_LENGTH - text.length());
    }

    public boolean isShort() {
        return this.puzzle.trim().length() <= 28;
    }

}