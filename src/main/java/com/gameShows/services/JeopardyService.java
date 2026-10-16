package com.gameShows.services;

import com.gameShows.models.jeopardy.Clue;
import com.gameShows.models.shared.Game;
import com.gameShows.models.shared.Player;
import com.gameShows.repositories.jeopardy.JeopardyClueRepository;
import com.gameShows.repositories.jeopardy.JeopardyGameRepository;
import com.gameShows.repositories.jeopardy.JeopardyPlayerRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

@Service
public class JeopardyService {

    private final JeopardyPlayerRepository playerRepository;
    private final JeopardyClueRepository clueRepository;
    private final JeopardyGameRepository gameRepository;

    public JeopardyService(
            JeopardyClueRepository clueRepository,
            JeopardyPlayerRepository playerRepository,
            JeopardyGameRepository gameRepository) {
        this.playerRepository = playerRepository;
        this.clueRepository = clueRepository;
        this.gameRepository = gameRepository;
    }

    public List<Clue> getCluesByCategory(String category) {
        return clueRepository.getCluesByCategory(category);
    }

    public List<Clue> getAllClues() {
        return clueRepository.getAllClues();
    }

    public Set<String> getAllCategories() {
        return clueRepository.getAllCategories();
    }

    public Set<String> getAllResponses() {
        return clueRepository.getAllResponses();
    }

    public List<Game> getAllGames() {
        return gameRepository.getAllGames();
    }

    public List<Player> getAllPlayers() {
        return playerRepository.getAllPlayers();
    }
}
