package com.gameShows.live.ws;

import com.gameShows.live.jeopardy.JeopardyEngine;
import com.gameShows.live.session.GameSession;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.stereotype.Controller;

import java.security.Principal;

@Controller
public class JeopardyWsController {

    private final LiveGameOrchestrator orchestrator;

    public JeopardyWsController(LiveGameOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    @MessageMapping("/session/{code}/jeopardy/selectClue")
    public void selectClue(@DestinationVariable String code, Principal principal, SelectClueRequest request) {
        orchestrator.act(code, session -> engine(session).selectClue(principal.getName(), request.cellId()));
    }

    @MessageMapping("/session/{code}/jeopardy/buzz")
    public void buzz(@DestinationVariable String code, Principal principal) {
        orchestrator.act(code, session -> engine(session).buzz(principal.getName()));
    }

    @MessageMapping("/session/{code}/jeopardy/answer")
    public void answer(@DestinationVariable String code, Principal principal, TextRequest request) {
        orchestrator.act(code, session -> engine(session).submitAnswer(principal.getName(), request.value()));
    }

    @MessageMapping("/session/{code}/jeopardy/dailyDoubleWager")
    public void dailyDoubleWager(@DestinationVariable String code, Principal principal, WagerRequest request) {
        orchestrator.act(code, session -> engine(session).submitDailyDoubleWager(principal.getName(), request.wager()));
    }

    @MessageMapping("/session/{code}/jeopardy/dailyDoubleAnswer")
    public void dailyDoubleAnswer(@DestinationVariable String code, Principal principal, TextRequest request) {
        orchestrator.act(code, session -> engine(session).submitDailyDoubleAnswer(principal.getName(), request.value()));
    }

    @MessageMapping("/session/{code}/jeopardy/finalWager")
    public void finalWager(@DestinationVariable String code, Principal principal, WagerRequest request) {
        orchestrator.act(code, session -> engine(session).submitFinalWager(principal.getName(), request.wager()));
    }

    @MessageMapping("/session/{code}/jeopardy/finalAnswer")
    public void finalAnswer(@DestinationVariable String code, Principal principal, TextRequest request) {
        orchestrator.act(code, session -> engine(session).submitFinalAnswer(principal.getName(), request.value()));
    }

    private JeopardyEngine engine(GameSession session) {
        return (JeopardyEngine) session.getEngine();
    }
}
