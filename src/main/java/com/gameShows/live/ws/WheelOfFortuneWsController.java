package com.gameShows.live.ws;

import com.gameShows.live.error.InvalidActionException;
import com.gameShows.live.session.GameSession;
import com.gameShows.live.wof.WofEngine;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.stereotype.Controller;

import java.security.Principal;

@Controller
public class WheelOfFortuneWsController {

    private final LiveGameOrchestrator orchestrator;

    public WheelOfFortuneWsController(LiveGameOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    @MessageMapping("/session/{code}/wof/spin")
    public void spin(@DestinationVariable String code, Principal principal) {
        orchestrator.act(code, session -> engine(session).spin(principal.getName()));
    }

    @MessageMapping("/session/{code}/wof/guessLetter")
    public void guessLetter(@DestinationVariable String code, Principal principal, CharacterRequest request) {
        orchestrator.act(code, session -> engine(session).guessConsonant(principal.getName(), firstChar(request.value())));
    }

    @MessageMapping("/session/{code}/wof/buyVowel")
    public void buyVowel(@DestinationVariable String code, Principal principal, CharacterRequest request) {
        orchestrator.act(code, session -> engine(session).buyVowel(principal.getName(), firstChar(request.value())));
    }

    @MessageMapping("/session/{code}/wof/playWildCard")
    public void playWildCard(@DestinationVariable String code, Principal principal) {
        orchestrator.act(code, session -> engine(session).playWildCard(principal.getName()));
    }

    @MessageMapping("/session/{code}/wof/beginSolve")
    public void beginSolve(@DestinationVariable String code, Principal principal) {
        orchestrator.act(code, session -> engine(session).beginSolve(principal.getName()));
    }

    @MessageMapping("/session/{code}/wof/solve")
    public void solve(@DestinationVariable String code, Principal principal, TextRequest request) {
        orchestrator.act(code, session -> engine(session).attemptSolve(principal.getName(), request.value()));
    }

    @MessageMapping("/session/{code}/wof/beginFrenzySolve")
    public void beginFrenzySolve(@DestinationVariable String code, Principal principal) {
        orchestrator.act(code, session -> engine(session).beginFrenzySolve(principal.getName()));
    }

    @MessageMapping("/session/{code}/wof/continueFrenzy")
    public void continueFrenzy(@DestinationVariable String code, Principal principal) {
        orchestrator.act(code, session -> engine(session).continueFrenzy(principal.getName()));
    }

    @MessageMapping("/session/{code}/wof/frenzyAnswer")
    public void frenzyAnswer(@DestinationVariable String code, Principal principal, TextRequest request) {
        orchestrator.act(code, session -> engine(session).attemptFrenzyAnswer(principal.getName(), request.value()));
    }

    @MessageMapping("/session/{code}/wof/beginTripleTossUpSolve")
    public void beginTripleTossUpSolve(@DestinationVariable String code, Principal principal) {
        orchestrator.act(code, session -> engine(session).beginTripleTossUpSolve(principal.getName()));
    }

    @MessageMapping("/session/{code}/wof/tripleTossUpAnswer")
    public void tripleTossUpAnswer(@DestinationVariable String code, Principal principal, TextRequest request) {
        orchestrator.act(code, session -> engine(session).attemptTripleTossUpAnswer(principal.getName(), request.value()));
    }

    @MessageMapping("/session/{code}/wof/continueRound")
    public void continueRound(@DestinationVariable String code, Principal principal) {
        orchestrator.act(code, session -> engine(session).continueToNextRound(principal.getName()));
    }

    @MessageMapping("/session/{code}/wof/finalSpin")
    public void finalSpin(@DestinationVariable String code, Principal principal) {
        orchestrator.act(code, session -> engine(session).finalSpin(principal.getName()));
    }

    @MessageMapping("/session/{code}/wof/speedupGuessLetter")
    public void speedupGuessLetter(@DestinationVariable String code, Principal principal, CharacterRequest request) {
        orchestrator.act(code, session -> engine(session).speedupGuessLetter(principal.getName(), firstChar(request.value())));
    }

    @MessageMapping("/session/{code}/wof/speedupSolve")
    public void speedupSolve(@DestinationVariable String code, Principal principal, TextRequest request) {
        orchestrator.act(code, session -> engine(session).speedupAttemptSolve(principal.getName(), request.value()));
    }

    @MessageMapping("/session/{code}/wof/bonusPickCategory")
    public void bonusPickCategory(@DestinationVariable String code, Principal principal, CategoryRequest request) {
        orchestrator.act(code, session -> engine(session).pickBonusCategory(principal.getName(), request.category()));
    }

    @MessageMapping("/session/{code}/wof/bonusSubmitLetters")
    public void bonusSubmitLetters(@DestinationVariable String code, Principal principal, BonusLettersRequest request) {
        orchestrator.act(code, session -> engine(session)
                .submitBonusLetters(principal.getName(), request.consonants(), firstChar(request.vowel())));
    }

    @MessageMapping("/session/{code}/wof/bonusSolve")
    public void bonusSolve(@DestinationVariable String code, Principal principal, TextRequest request) {
        orchestrator.act(code, session -> engine(session).attemptBonusSolve(principal.getName(), request.value()));
    }

    private char firstChar(String value) {
        if (value == null || value.isEmpty()) {
            throw new InvalidActionException("A letter is required.");
        }
        return value.charAt(0);
    }

    private WofEngine engine(GameSession session) {
        return (WofEngine) session.getEngine();
    }
}
