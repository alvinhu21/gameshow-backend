package com.gameShows.models.jeopardy;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "clues") // This defines the table name in SQLite
public class Clue {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String category;

    private String clueText;

    private String response;

    /**
     * Normally null: if the response belongs to one of the response pools (a state, a country, a
     * president...) the clue is answered by picking from a list, detected automatically. Set it to a
     * pool id to force that pool, or to "none" to keep the clue a typed answer.
     */
    private String responsePool;

    /**
     * Normally null: the clue is judged against its response. Set it, as JSON, when more than one
     * response is acceptable or several are needed:
     * <pre>
     *   {"anyOf": ["Krone", "Krona"]}                              either one is right
     *   {"allOf": ["St. Joseph", "Sacramento"]}                    both are needed, in any order
     *   {"allOf": [["St. Joseph", "Saint Joseph"], "Sacramento"]}  an inner list is one part's spellings
     * </pre>
     * Once set it alone decides what's right, and the response is only what's shown when the clue
     * is revealed. A clue with alternatives, or more than one part, is always answered by typing.
     * See {@code AnswerKey}.
     */
    private String acceptedResponses;

    // Standard Getters and Setters
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public String getResponse() {
        return response;
    }

    public void setResponse(String response) {
        this.response = response;
    }

    public String getClueText() {
        return clueText;
    }

    public void setClueText(String clueText) {
        this.clueText = clueText;
    }

    public String getResponsePool() {
        return responsePool;
    }

    public void setResponsePool(String responsePool) {
        this.responsePool = responsePool;
    }

    public String getAcceptedResponses() {
        return acceptedResponses;
    }

    public void setAcceptedResponses(String acceptedResponses) {
        this.acceptedResponses = acceptedResponses;
    }

}