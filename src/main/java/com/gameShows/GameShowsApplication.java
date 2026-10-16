package com.gameShows;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class GameShowsApplication {

	public static void main(String[] args) {
		SpringApplication.run(GameShowsApplication.class, args);
	}

}
