package com.gameShows.repositories.jeopardy;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import com.gameShows.models.jeopardy.Clue;

import jakarta.persistence.LockModeType;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.Set;

@Repository
public interface JeopardyClueRepository extends JpaRepository<Clue, Long> {

        @Transactional // Added: Required for PESSIMISTIC_WRITE
        @Lock(LockModeType.PESSIMISTIC_WRITE)
        @Query("SELECT p FROM Clue p WHERE p.id = :id")
        Optional<Clue> findByIdForUpdate(@Param("id") Long id); // Fixed: Should return Optional<Clue>, not Player

        @Modifying
        @Transactional
        @Query("UPDATE Clue p SET p.category = :category, p.clueText = :clueText, p.response = :response WHERE p.id = :id")
        int updateClueDirectly(@Param("id") Long id,
                        @Param("category") String category,
                        @Param("clueText") String clueText,
                        @Param("response") String response);

        @Modifying
        @Transactional
        @Query(value = "INSERT INTO clueTexts (id, category, clueText) VALUES (:id, :category, :clueText)", nativeQuery = true)
        void insertClue(@Param("id") Long id,
                        @Param("category") String category,
                        @Param("clueText") String clueText,
                        @Param("response") String response);

        @Transactional // Added: Required for PESSIMISTIC_WRITE
        @Lock(LockModeType.PESSIMISTIC_WRITE)
        @Query("SELECT p FROM Clue p WHERE LOWER(p.category) = LOWER(:category)")
        List<Clue> getCluesByCategory(@Param("category") String category);

        @Transactional // Added: Required for PESSIMISTIC_WRITE
        @Lock(LockModeType.PESSIMISTIC_WRITE)
        @Query("SELECT p FROM Clue p")
        List<Clue> getAllClues();

        @Transactional // Added: Required for PESSIMISTIC_WRITE
        @Lock(LockModeType.PESSIMISTIC_WRITE)
        @Query("SELECT DISTINCT p.category FROM Clue p")
        Set<String> getAllCategories();

        @Transactional // Added: Required for PESSIMISTIC_WRITE
        @Lock(LockModeType.PESSIMISTIC_WRITE)
        @Query("SELECT DISTINCT p.response FROM Clue p")
        Set<String> getAllResponses();
}