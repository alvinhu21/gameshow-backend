package com.gameShows.models.shared;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

public interface Game {
    // Standard Getters and Setters
    public Long getId();

    public void setId(Long id);

    public String getRoundId();

    public void setRoundId(String roundId);

    public boolean isCompleted();

    public void setCompleted(boolean completed);

}