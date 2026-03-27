package com.gameShows.repositories.jeopardy;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import com.gameShows.models.jeopardy.JeopardyGameImpl;
import com.gameShows.models.shared.Game;
import com.gameShows.models.shared.Player;

import jakarta.persistence.LockModeType;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface JeopardyGameRepository extends JpaRepository<JeopardyGameImpl, Long> {

        @Lock(LockModeType.PESSIMISTIC_WRITE)
        @Query("SELECT p FROM JeopardyGameImpl p WHERE p.id = :id")
        Optional<Player> findByIdForUpdate(@Param("id") Long id);

        @Transactional // Added: Required for PESSIMISTIC_WRITE
        @Lock(LockModeType.PESSIMISTIC_WRITE)
        @Query("SELECT p FROM JeopardyGameImpl p")
        List<Game> getAllGames();

        /**
         * Updates an existing player.
         * Changed return type to int to track if the player actually existed.
         */
        @Modifying
        @Transactional
        @Query("UPDATE JeopardyGameImpl g SET g.roundId=:roundId, g.completed=:completed WHERE g.id = :id")
        int updateGameDirectly(@Param("id") Long id,
                        @Param("completed") Boolean completed,
                        @Param("roundId") String roundId);

        /**
         * Explicit native insert.
         * Note: Generally, using repository.save(player) is preferred over this.
         */
        @Modifying
        @Transactional
        @Query(value = "INSERT INTO games (id, completed, roundId) VALUES (:id, :completed, :roundId)", nativeQuery = true)
        void insertGame(@Param("id") Long id,
                        @Param("completed") Boolean completed,
                        @Param("roundId") String roundId);
}