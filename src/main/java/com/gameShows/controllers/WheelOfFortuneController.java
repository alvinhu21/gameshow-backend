package com.gameShows.controllers;

import org.springframework.web.bind.annotation.*;

import com.gameShows.models.shared.Game;
import com.gameShows.models.shared.Player;
import com.gameShows.models.wof.Puzzle;
import com.gameShows.services.WheelOfFortuneService;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@RestController
@RequestMapping("/wheelOfFortune")
@CrossOrigin(origins = "*")
public class WheelOfFortuneController {

    private final WheelOfFortuneService service;

    public WheelOfFortuneController(WheelOfFortuneService service) {
        this.service = service;
    }

    @GetMapping(value = "/puzzles")
    public List<Puzzle> getPuzzles(@RequestParam(name = "category", required = false) String category) {
        if (category != null && !category.isEmpty()) {
            List<Puzzle> puzzles = this.service.getPuzzlesByCategory(category);
            if (puzzles.isEmpty()) {
                return this.service.getAllPuzzles();
            } else {
                return puzzles;
            }
        }

        return this.service.getAllPuzzles();
    }

    @GetMapping(value = "/categories")
    public Set<String> getAllCategories() {
        return this.service.getAllCategories();
    }

    @GetMapping(value = "/games")
    public List<Game> getAllGames() {
        return this.service.getAllGames();
    }

    @GetMapping(value = "/players")
    public List<Player> getAllPlayers() {
        return this.service.getAllPlayers();
    }
}