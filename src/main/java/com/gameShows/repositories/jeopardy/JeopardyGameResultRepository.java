package com.gameShows.repositories.jeopardy;

import com.gameShows.models.jeopardy.JeopardyGameResult;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface JeopardyGameResultRepository extends JpaRepository<JeopardyGameResult, Long> {
}
