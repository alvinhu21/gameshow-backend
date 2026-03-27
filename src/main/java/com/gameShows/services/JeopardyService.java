package com.gameShows.services;

import com.gameShows.models.jeopardy.Clue;
import com.gameShows.models.shared.Game;
import com.gameShows.models.shared.Player;
import com.gameShows.repositories.jeopardy.JeopardyPlayerRepository;
import com.gameShows.repositories.jeopardy.JeopardyClueRepository;
import com.gameShows.repositories.jeopardy.JeopardyGameRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
public class JeopardyService {

    private static final Logger logger = LoggerFactory.getLogger(JeopardyService.class);
    private final JeopardyPlayerRepository jPlayerRepository;
    private final JeopardyClueRepository jClueRepository;
    private final JeopardyGameRepository jGameRepository;

    public JeopardyService(JeopardyClueRepository jClueRepository,
            JeopardyPlayerRepository jPlayerRepository,
            JeopardyGameRepository jGameRepository) {
        this.jPlayerRepository = jPlayerRepository;
        this.jClueRepository = jClueRepository;
        this.jGameRepository = jGameRepository;
    }

    public List<Clue> getCluesByCategory(String category) {
        return this.jClueRepository.getCluesByCategory(category);
    }

    public List<Clue> getAllClues() {
        return this.jClueRepository.getAllClues();
    }

    public Set<String> getAllCategories() {
        return this.jClueRepository.getAllCategories();
    }

    public Set<String> getAllResponses() {
        return this.jClueRepository.getAllResponses();
    }

    public List<Game> getAllGames() {
        return this.jGameRepository.getAllGames();
    }

    public List<Player> getAllPlayers() {
        return this.jPlayerRepository.getAllPlayers();
    }

}