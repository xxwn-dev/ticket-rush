package com.xxwn.ticket_rush.domain.seat;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

public interface SeatRepository extends JpaRepository<Seat, Long> {
    List<Seat> findAllByEventId(Long eventId);

    long countByEventId(Long eventId);

    @Query("select s.id from Seat s where s.event.id = :eventId and s.status = 'AVAILABLE'")
    List<Long> findAvailableSeatIds(@Param("eventId") Long eventId);

    @Query("select s from Seat s join fetch s.event where s.id = :seatId and s.event.id = :eventId")
    Optional<Seat> findByIdAndEventId(@Param("seatId") Long seatId, @Param("eventId") Long eventId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Seat s where s.id = :id")
    Optional<Seat> findByIdWithLock(Long id);

    @Modifying
    @Transactional
    @Query("DELETE FROM Seat s WHERE s.event.id IN :eventIds")
    void deleteByEventIdIn(@Param("eventIds") List<Long> eventIds);
}

