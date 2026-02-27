package com.xxwn.ticketing_app.domain.seat;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface SeatRepository extends JpaRepository<Seat, Long> {
    List<Seat> findAllByConcertId(Long concertId);

    @Query("select s.id from Seat s where s.concert.id = :concertId and s.status = 'AVAILABLE'")
    List<Long> findAvailableSeatIds(@Param("concertId") Long concertId);

    @Query("select s from Seat s join fetch s.concert where s.id = :seatId and s.concert.id = :concertId")
    Optional<Seat> findByIdAndConcertId(@Param("seatId") Long seatId, @Param("concertId") Long concertId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Seat s where s.id = :id")
    Optional<Seat> findByIdWithLock(Long id);
}

