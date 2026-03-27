package com.gameShows.models.jeopardy;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import com.fasterxml.jackson.annotation.JsonBackReference;
import com.gameShows.models.shared.Game;
import com.gameShows.models.shared.Player;

@Entity
@Table(name = "players") // This defines the table name in SQLite
public class JeopardyPlayerImpl implements Player {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String firstName;
    private String lastName;
    private Integer score;

    @ManyToOne
    @JoinColumn(name = "gameId", referencedColumnName = "id")
    @JsonBackReference
    private JeopardyGameImpl game;

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

    public Game getGame() {
        return this.game;
    }

    public void setGame(JeopardyGameImpl game) {
        this.game = game;
    }

}