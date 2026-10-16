package com.gameShows.services;

import com.gameShows.api.PageResponse;
import com.gameShows.api.PlayerResultView;
import com.gameShows.live.session.CompletedGame;
import com.gameShows.models.jeopardy.JeopardyGameResult;
import com.gameShows.models.jeopardy.JeopardyPlayerResult;
import com.gameShows.repositories.jeopardy.JeopardyGameResultRepository;
import com.gameShows.repositories.jeopardy.JeopardyPlayerResultRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** Finished live Jeopardy games, as stored in jeopardy.db. */
@Service
public class JeopardyResultsService {

    public static final int DEFAULT_PAGE_SIZE = 25;
    public static final int MAX_PAGE_SIZE = 100;

    private static final Logger log = LoggerFactory.getLogger(JeopardyResultsService.class);

    private final JeopardyGameResultRepository gameResultRepository;
    private final JeopardyPlayerResultRepository playerResultRepository;

    public JeopardyResultsService(JeopardyGameResultRepository gameResultRepository,
            JeopardyPlayerResultRepository playerResultRepository) {
        this.gameResultRepository = gameResultRepository;
        this.playerResultRepository = playerResultRepository;
    }

    /**
     * Saves how each human contestant finished, all-or-nothing. AI opponents aren't stored, but they
     * still count toward the places (a person beaten by a bot finished second) and contestantCount.
     */
    @Transactional(transactionManager = "jTransactionManager")
    public void recordGame(String sessionCode, CompletedGame game) {
        List<CompletedGame.Standing> humans = game.standings().stream().filter(s -> !s.ai()).toList();
        if (humans.isEmpty()) {
            log.info("Skipping Jeopardy result with no human players: sessionCode={}", sessionCode);
            return;
        }
        JeopardyGameResult result = new JeopardyGameResult(sessionCode, game.completedAt(), game.roundCount(),
                game.standings().size());
        for (CompletedGame.Standing standing : humans) {
            result.addPlayerResult(new JeopardyPlayerResult(standing.displayName(), standing.finalScore(),
                    standing.place(), standing.tied()));
        }
        JeopardyGameResult saved = gameResultRepository.save(result);
        log.info("Recorded Jeopardy result: gameResultId={} sessionCode={} humanPlayers={} contestants={}",
                saved.getId(), sessionCode, humans.size(), game.standings().size());
    }

    /** One page of every recorded finish, newest game first and each game's players best-first. */
    @Transactional(transactionManager = "jTransactionManager", readOnly = true)
    public PageResponse<PlayerResultView> getPlayerResults(int page, int size) {
        return PageResponse.from(playerResultRepository.findAllNewestGameFirst(PageRequest.of(page, size))
                .map(PlayerResultView::from));
    }
}
