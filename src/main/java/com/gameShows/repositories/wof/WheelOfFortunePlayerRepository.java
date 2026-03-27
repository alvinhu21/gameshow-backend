package com.gameShows.repositories.wof;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import com.gameShows.models.shared.Player;
import com.gameShows.models.wof.WheelOfFortunePlayerImpl;

import jakarta.persistence.LockModeType;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface WheelOfFortunePlayerRepository extends JpaRepository<WheelOfFortunePlayerImpl, Long> {

        @Lock(LockModeType.PESSIMISTIC_WRITE)
        @Query("SELECT p FROM WheelOfFortunePlayerImpl p WHERE p.id = :id")
        Optional<Player> findByIdForUpdate(@Param("id") Long id);

        @Transactional // Added: Required for PESSIMISTIC_WRITE
        @Lock(LockModeType.PESSIMISTIC_WRITE)
        @Query("SELECT p FROM WheelOfFortunePlayerImpl p")
        List<Player> getAllPlayers();

        /**
         * Updates an existing player.
         * Changed return type to int to track if the player actually existed.
         */
        @Modifying
        @Transactional
        @Query("UPDATE WheelOfFortunePlayerImpl p SET p.score = :score, p.firstName = :firstName, p.lastName = :lastName WHERE p.id = :id")
        int updatePlayerDirectly(@Param("id") Long id,
                        @Param("firstName") String firstName,
                        @Param("lastName") String lastName,
                        @Param("score") Integer score);

        /**
         * Explicit native insert.
         * Note: Generally, using repository.save(player) is preferred over this.
         */
        @Modifying
        @Transactional
        @Query(value = "INSERT INTO players (id, first_name, last_name, score) VALUES (:id, :firstName, :lastName, :score)", nativeQuery = true)
        void insertPlayer(@Param("id") Long id,
                        @Param("firstName") String firstName,
                        @Param("lastName") String lastName,
                        @Param("score") Integer score);
}