package com.gameShows.controllers;

import org.springframework.web.bind.annotation.*;

import com.gameShows.models.jeopardy.Clue;
import com.gameShows.models.shared.Game;
import com.gameShows.models.shared.Player;
import com.gameShows.services.JeopardyService;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@RestController
@RequestMapping("/jeopardy")
@CrossOrigin(origins = "*")
public class JeopardyController {

    private final JeopardyService service;

    public JeopardyController(JeopardyService service) {
        this.service = service;
    }

    @GetMapping(value = "/clues")
    public List<Clue> getClues(@RequestParam(name = "category", required = false) String category) {
        if (category != null && !category.isEmpty()) {
            List<Clue> puzzles = this.service.getCluesByCategory(category);
            if (puzzles.isEmpty()) {
                return this.service.getAllClues();
            } else {
                return puzzles;
            }
        }

        return this.service.getAllClues();
    }

    @GetMapping(value = "/categories")
    public Set<String> getAllCategories() {
        return this.service.getAllCategories();
    }

    @GetMapping(value = "/responses")
    public Set<String> getAllResponses() {
        return this.service.getAllResponses();
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