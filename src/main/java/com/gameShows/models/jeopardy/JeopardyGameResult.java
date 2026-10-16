package com.gameShows.models.jeopardy;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** One finished live Jeopardy game. Its human contestants' finishes are {@link JeopardyPlayerResult}s. */
@Entity
@Table(name = "game_results")
public class JeopardyGameResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The code the game was played under. Codes are only unique among live games, so this
     *  identifies the game for people, not for the database. */
    @Column(nullable = false, length = 16)
    private String sessionCode;

    @Column(nullable = false)
    private Instant completedAt;

    /** Regular rounds played before Final Jeopardy. */
    @Column(nullable = false)
    private int roundCount;

    /** Everyone who played, AI opponents included - what each place is "out of". */
    @Column(nullable = false)
    private int contestantCount;

    @OneToMany(mappedBy = "game", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("place ASC")
    private List<JeopardyPlayerResult> playerResults = new ArrayList<>();

    protected JeopardyGameResult() {
        // For JPA.
    }

    public JeopardyGameResult(String sessionCode, Instant completedAt, int roundCount, int contestantCount) {
        this.sessionCode = sessionCode;
        this.completedAt = completedAt;
        this.roundCount = roundCount;
        this.contestantCount = contestantCount;
    }

    /** Keeps both sides of the relationship in sync. */
    public void addPlayerResult(JeopardyPlayerResult result) {
        playerResults.add(result);
        result.setGame(this);
    }

    public Long getId() {
        return id;
    }

    public String getSessionCode() {
        return sessionCode;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public int getRoundCount() {
        return roundCount;
    }

    public int getContestantCount() {
        return contestantCount;
    }

    public List<JeopardyPlayerResult> getPlayerResults() {
        return playerResults;
    }
}
