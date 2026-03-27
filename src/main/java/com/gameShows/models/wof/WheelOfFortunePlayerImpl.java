package com.gameShows.models.wof;

import com.fasterxml.jackson.annotation.JsonBackReference;
import com.gameShows.models.jeopardy.JeopardyGameImpl;
import com.gameShows.models.shared.Game;
import com.gameShows.models.shared.Player;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "players") // This defines the table name in SQLite
public class WheelOfFortunePlayerImpl implements Player {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String firstName;
    private String lastName;
    private Integer score;

    @ManyToOne
    @JoinColumn(name = "gameId", referencedColumnName = "id")
    @JsonBackReference
    private WheelOfFortuneGameImpl game;

    // Standard Getters and Setters
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getFirstName() {
        return firstName;
    }

    public void setFirstName(String firstName) {
        this.firstName = firstName;
    }

    public String getLastName() {
        return lastName;
    }

    public void setLastName(String lastName) {
        this.lastName = lastName;
    }

    public Integer getScore() {
        return this.score;
    }

    public void setScore(Integer score) {
        this.score = score;
    }

    public WheelOfFortuneGameImpl getGame() {
        return this.game;
    }

    public void setGame(WheelOfFortuneGameImpl game) {
        this.game = game;
    }
}