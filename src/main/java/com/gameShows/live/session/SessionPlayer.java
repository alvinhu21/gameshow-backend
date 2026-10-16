package com.gameShows.live.session;

public class SessionPlayer {

    private final String playerId;
    private final String displayName;
    private final boolean host;
    private final boolean ai;
    private int score = 0;
    private boolean connected = true;

    public SessionPlayer(String playerId, String displayName, boolean host) {
        this(playerId, displayName, host, false);
    }

    private SessionPlayer(String playerId, String displayName, boolean host, boolean ai) {
        this.playerId = playerId;
        this.displayName = displayName;
        this.host = host;
        this.ai = ai;
    }

    /** AI opponents are never the host and always report as connected - they have no real socket to drop. */
    public static SessionPlayer ai(String playerId, String displayName) {
        return new SessionPlayer(playerId, displayName, false, true);
    }

    public String getPlayerId() {
        return playerId;
    }

    public String getDisplayName() {
        return displayName;
    }

    public boolean isHost() {
        return host;
    }

    public boolean isAi() {
        return ai;
    }

    public int getScore() {
        return score;
    }

    public void setScore(int score) {
        this.score = score;
    }

    public void addToScore(int delta) {
        this.score += delta;
    }

    public boolean isConnected() {
        return connected;
    }

    public void setConnected(boolean connected) {
        this.connected = connected;
    }
}
