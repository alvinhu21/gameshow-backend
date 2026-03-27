package com.gameShows.repositories.wof;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import com.gameShows.models.wof.Puzzle;

import jakarta.persistence.LockModeType;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.Set;

@Repository
public interface WheelOfFortunePuzzleRepository extends JpaRepository<Puzzle, Long> {

        @Transactional // Added: Required for PESSIMISTIC_WRITE
        @Lock(LockModeType.PESSIMISTIC_WRITE)
        @Query("SELECT p FROM Puzzle p WHERE p.id = :id")
        Optional<Puzzle> findByIdForUpdate(@Param("id") Long id); // Fixed: Should return Optional<Puzzle>, not Player

        @Modifying
        @Transactional
        @Query("UPDATE Puzzle p SET p.category = :category, p.puzzle = :puzzle WHERE p.id = :id")
        int updatePuzzleDirectly(@Param("id") Long id,
                        @Param("category") String category,
                        @Param("puzzle") String puzzle);

        @Modifying
        @Transactional
        @Query(value = "INSERT INTO puzzles (id, category, puzzle) VALUES (:id, :category, :puzzle)", nativeQuery = true)
        void insertPuzzle(@Param("id") Long id,
                        @Param("category") String category,
                        @Param("puzzle") String puzzle);

        @Transactional // Added: Required for PESSIMISTIC_WRITE
        @Lock(LockModeType.PESSIMISTIC_WRITE)
        @Query("SELECT p FROM Puzzle p WHERE LOWER(p.category) = LOWER(:category)")
        List<Puzzle> getPuzzlesByCategory(@Param("category") String category);

        @Transactional // Added: Required for PESSIMISTIC_WRITE
        @Lock(LockModeType.PESSIMISTIC_WRITE)
        @Query("SELECT p FROM Puzzle p")
        List<Puzzle> getAllPuzzles();

        @Transactional // Added: Required for PESSIMISTIC_WRITE
        @Lock(LockModeType.PESSIMISTIC_WRITE)
        @Query("SELECT DISTINCT p.category FROM Puzzle p")
        Set<String> getAllCategories();
}