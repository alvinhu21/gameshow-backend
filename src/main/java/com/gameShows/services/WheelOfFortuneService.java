package com.gameShows.services;

import com.gameShows.models.shared.Game;
import com.gameShows.models.shared.Player;
import com.gameShows.models.wof.Puzzle;
import com.gameShows.models.wof.Wedge;
import com.gameShows.repositories.wof.WheelOfFortuneGameRepository;
import com.gameShows.repositories.wof.WheelOfFortunePlayerRepository;
import com.gameShows.repositories.wof.WheelOfFortunePuzzleRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

@Service
public class WheelOfFortuneService {

    private final WheelOfFortunePlayerRepository playerRepository;
    private final WheelOfFortunePuzzleRepository puzzleRepository;
    private final WheelOfFortuneGameRepository gameRepository;

    public WheelOfFortuneService(
            WheelOfFortunePuzzleRepository puzzleRepository,
            WheelOfFortunePlayerRepository playerRepository,
            WheelOfFortuneGameRepository gameRepository) {
        this.playerRepository = playerRepository;
        this.puzzleRepository = puzzleRepository;
        this.gameRepository = gameRepository;
    }

    public List<Puzzle> getPuzzlesByCategory(String category) {
        return puzzleRepository.getPuzzlesByCategory(category);
    }

    public List<Puzzle> getAllPuzzles() {
        return puzzleRepository.getAllPuzzles();
    }

    public Set<String> getAllCategories() {
        return puzzleRepository.getAllCategories();
    }

    public List<Game> getAllGames() {
        return gameRepository.getAllGames();
    }

    public List<Player> getAllPlayers() {
        return playerRepository.getAllPlayers();
    }

    public List<Wedge> getWheel(int roundNumber) {
        return Wedge.buildSyndicatedWheel(roundNumber);
    }
}
