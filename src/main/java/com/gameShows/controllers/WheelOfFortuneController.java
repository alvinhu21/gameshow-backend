package com.gameShows.controllers;

import com.gameShows.models.shared.Game;
import com.gameShows.models.shared.Player;
import com.gameShows.models.wof.Puzzle;
import com.gameShows.models.wof.Wedge;
import com.gameShows.services.WheelOfFortuneService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Set;

@RestController
@RequestMapping("/wheelOfFortune")
public class WheelOfFortuneController {

    private final WheelOfFortuneService service;

    public WheelOfFortuneController(WheelOfFortuneService service) {
        this.service = service;
    }

    @GetMapping("/puzzles")
    public ResponseEntity<List<Puzzle>> getPuzzles(@RequestParam(name = "category", required = false) String category) {
        List<Puzzle> puzzles = (category == null || category.isBlank())
                ? service.getAllPuzzles()
                : service.getPuzzlesByCategory(category);

        return ResponseEntity.ok(puzzles);
    }

    @GetMapping("/wheel")
    public ResponseEntity<List<Wedge>> getWheel(@RequestParam(name = "round", defaultValue = "1") int roundNumber) {
        return ResponseEntity.ok(service.getWheel(roundNumber));
    }

    @GetMapping("/categories")
    public ResponseEntity<Set<String>> getAllCategories() {
        return ResponseEntity.ok(service.getAllCategories());
    }

    @GetMapping("/games")
    public ResponseEntity<List<Game>> getAllGames() {
        return ResponseEntity.ok(service.getAllGames());
    }

    @GetMapping("/players")
    public ResponseEntity<List<Player>> getAllPlayers() {
        return ResponseEntity.ok(service.getAllPlayers());
    }
}
