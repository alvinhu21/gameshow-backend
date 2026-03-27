package com.gameShows.models.shared;

public interface Player {
    // Standard Getters and Setters
    public Long getId();

    public void setId(Long id);

    public String getFirstName();

    public void setFirstName(String firstName);

    public String getLastName();

    public void setLastName(String lastName);

    public Integer getScore();

    public void setScore(Integer score);

}