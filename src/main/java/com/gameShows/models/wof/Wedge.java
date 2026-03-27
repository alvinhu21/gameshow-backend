package com.gameShows.models.wof;

public class Wedge {
    public String label = "";
    public Integer value = 0;
    public String backgroundColor = "#ffffff";
    public String color = "#000000";

    public Wedge(String label, Integer value, String backgroundColor, String color) {
        this.label = label;
        this.value = value;
        this.backgroundColor = backgroundColor;
        this.color = color;
    }

    public String getLabel() {
        return this.label;
    }

    public Integer getValue() {
        return this.value;
    }

    public String getBackgroundColor() {
        return this.backgroundColor;
    }

    public String getColor() {
        return this.color;
    }
}
