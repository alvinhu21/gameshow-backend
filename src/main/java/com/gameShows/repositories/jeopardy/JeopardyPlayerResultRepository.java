package com.gameShows.repositories.jeopardy;

import com.gameShows.models.jeopardy.JeopardyPlayerResult;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

@Repository
public interface JeopardyPlayerResultRepository extends JpaRepository<JeopardyPlayerResult, Long> {

    /**
     * Every recorded finish, newest game first (ids only grow, so they order games by when they
     * were recorded) and each game's players best-first. The game is fetched in the same query, so
     * a page costs one select plus the count rather than one more per row.
     */
    @Query(value = "SELECT r FROM JeopardyPlayerResult r JOIN FETCH r.game g "
            + "ORDER BY g.id DESC, r.place ASC, r.displayName ASC",
            countQuery = "SELECT COUNT(r) FROM JeopardyPlayerResult r")
    Page<JeopardyPlayerResult> findAllNewestGameFirst(Pageable pageable);
}
