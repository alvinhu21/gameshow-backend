package com.gameShows.controllers;

import com.gameShows.api.PageResponse;
import com.gameShows.api.PlayerResultView;
import com.gameShows.models.jeopardy.Clue;
import com.gameShows.models.shared.Game;
import com.gameShows.models.shared.Player;
import com.gameShows.services.JeopardyResultsService;
import com.gameShows.services.JeopardyService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Set;

@RestController
@RequestMapping("/jeopardy")
public class JeopardyController {

    private final JeopardyService service;
    private final JeopardyResultsService resultsService;

    public JeopardyController(JeopardyService service, JeopardyResultsService resultsService) {
        this.service = service;
        this.resultsService = resultsService;
    }

    /** Every human contestant's finish in a live game, newest game first, one page at a time. */
    @GetMapping("/results")
    public ResponseEntity<PageResponse<PlayerResultView>> getResults(
            @RequestParam(name = "page", defaultValue = "0") @Min(0) int page,
            @RequestParam(name = "size", defaultValue = "" + JeopardyResultsService.DEFAULT_PAGE_SIZE)
            @Min(1) @Max(JeopardyResultsService.MAX_PAGE_SIZE) int size) {
        return ResponseEntity.ok(resultsService.getPlayerResults(page, size));
    }

    @GetMapping("/clues")
    public ResponseEntity<List<Clue>> getClues(@RequestParam(name = "category", required = false) String category) {
        List<Clue> clues = (category == null || category.isBlank())
                ? service.getAllClues()
                : service.getCluesByCategory(category);

        return ResponseEntity.ok(clues);
    }

    @GetMapping("/categories")
    public ResponseEntity<Set<String>> getAllCategories() {
        return ResponseEntity.ok(service.getAllCategories());
    }

    @GetMapping("/responses")
    public ResponseEntity<Set<String>> getAllResponses() {
        return ResponseEntity.ok(service.getAllResponses());
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
