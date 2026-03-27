package com.gameShows.models.wof;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonManagedReference;
import com.gameShows.models.jeopardy.JeopardyPlayerImpl;
import com.gameShows.models.shared.Game;

@Entity
@Table(name = "players") // This defines the table name in SQLite
public class WheelOfFortuneGameImpl implements Game {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String roundId;
    private boolean completed;

    @OneToMany(mappedBy = "game", cascade = CascadeType.ALL, orphanRemoval = true)
    @JsonManagedReference
    private List<WheelOfFortunePlayerImpl> players = new ArrayList<>();

    // Standard Getters and Setters
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    @Override
    public String getRoundId() {
        return this.roundId;
    }

    @Override
    public void setRoundId(String roundId) {
        this.roundId = roundId;
    }

    @Override
    public boolean isCompleted() {
        return this.completed;
    }

    @Override
    public void setCompleted(boolean completed) {
        this.completed = completed;
    }

    public List<WheelOfFortunePlayerImpl> getPlayers() {
        return players;
    }

    public void setPlayers(List<WheelOfFortunePlayerImpl> players) {
        this.players = players;
    }

    /**
     * Helper method to keep both sides of the relationship in sync.
     */
    public void addPlayer(WheelOfFortunePlayerImpl player) {
        players.add(player);
        player.setGame(this);
    }

    public void removePlayer(WheelOfFortunePlayerImpl player) {
        players.remove(player);
        player.setGame(null);
    }
}