package com.gameShows.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gameShows.live.jeopardy.choices.PooledResponseChoiceDealer;
import com.gameShows.live.jeopardy.choices.ResponseChoiceDealer;
import com.gameShows.live.jeopardy.choices.ResponsePoolCatalog;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;

/** Loads the Jeopardy response pools once at startup and shares them across every game. */
@Configuration
public class ResponsePoolConfig {

    @Bean
    public ResponsePoolCatalog responsePoolCatalog(ObjectMapper objectMapper) throws IOException {
        return ResponsePoolCatalog.fromClasspath(objectMapper);
    }

    @Bean
    public ResponseChoiceDealer responseChoiceDealer(ResponsePoolCatalog responsePoolCatalog) {
        return new PooledResponseChoiceDealer(responsePoolCatalog);
    }
}
