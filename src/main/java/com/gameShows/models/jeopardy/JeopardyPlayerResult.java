package com.gameShows.models.jeopardy;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/** How one human contestant finished one {@link JeopardyGameResult}. */
@Entity
@Table(name = "player_results", indexes = @Index(name = "idx_player_results_game", columnList = "gameResultId"))
public class JeopardyPlayerResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "gameResultId", nullable = false)
    private JeopardyGameResult game;

    @Column(nullable = false, length = 24)
    private String displayName;

    @Column(nullable = false)
    private int finalScore;

    /** 1 is first. Tied scores share a place (1, 1, 3) - see {@code CompletedGame.Standing}. */
    @Column(nullable = false)
    private int place;

    /** Whether someone else - possibly an AI, which isn't stored - finished on the same score. */
    @Column(nullable = false)
    private boolean tied;

    protected JeopardyPlayerResult() {
        // For JPA.
    }

    public JeopardyPlayerResult(String displayName, int finalScore, int place, boolean tied) {
        this.displayName = displayName;
        this.finalScore = finalScore;
        this.place = place;
        this.tied = tied;
    }

    public Long getId() {
        return id;
    }

    public JeopardyGameResult getGame() {
        return game;
    }

    void setGame(JeopardyGameResult game) {
        this.game = game;
    }

    public String getDisplayName() {
        return displayName;
    }

    public int getFinalScore() {
        return finalScore;
    }

    public int getPlace() {
        return place;
    }

    public boolean isTied() {
        return tied;
    }
}
