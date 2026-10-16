package com.gameShows.live.session;

public record PlayerView(String playerId, String displayName, int score, boolean connected, boolean host, boolean ai) {

    public static PlayerView from(SessionPlayer player) {
        return new PlayerView(player.getPlayerId(), player.getDisplayName(), player.getScore(),
                player.isConnected(), player.isHost(), player.isAi());
    }
}
