package com.gameShows.services;

import com.gameShows.models.shared.Game;
import com.gameShows.models.shared.Player;
import com.gameShows.models.wof.Puzzle;
import com.gameShows.repositories.wof.WheelOfFortuneGameRepository;
import com.gameShows.repositories.wof.WheelOfFortunePlayerRepository;
import com.gameShows.repositories.wof.WheelOfFortunePuzzleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
public class WheelOfFortuneService {

    private static final Logger logger = LoggerFactory.getLogger(WheelOfFortuneService.class);
    private final WheelOfFortunePlayerRepository wofPlayerRepository;
    private final WheelOfFortunePuzzleRepository wofPuzzleRepository;
    private final WheelOfFortuneGameRepository wofGameRepository;

    public WheelOfFortuneService(WheelOfFortunePuzzleRepository wofPuzzleRepository,
            WheelOfFortunePlayerRepository wofPlayerRepository,
            WheelOfFortuneGameRepository wofGameRepository) {
        this.wofPlayerRepository = wofPlayerRepository;
        this.wofPuzzleRepository = wofPuzzleRepository;
        this.wofGameRepository = wofGameRepository;
    }

    public List<Puzzle> getPuzzlesByCategory(String category) {
        return this.wofPuzzleRepository.getPuzzlesByCategory(category);
    }

    public List<Puzzle> getAllPuzzles() {
        return this.wofPuzzleRepository.getAllPuzzles();
    }

    public Set<String> getAllCategories() {
        return this.wofPuzzleRepository.getAllCategories();
    }

    public List<Game> getAllGames() {
        return this.wofGameRepository.getAllGames();
    }

    public List<Player> getAllPlayers() {
        return this.wofPlayerRepository.getAllPlayers();
    }

}