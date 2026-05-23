package com.xxwn.ticket_rush.domain.booking;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

public interface BookingRepository extends JpaRepository<Booking, Long> {
    @Query("select count(b) from Booking b where b.seat.event.id = :eventId and b.seat.id = :seatId")
    long countByEventIdAndSeatId(@Param("eventId") Long eventId, @Param("seatId") Long seatId);

    Long deleteByUserIdAndSeatId(String userId, Long seatId);

    Optional<Booking> findByUserIdAndSeatIdAndStatus(String userId, Long seatId, BookingStatus status);

    @Modifying
    @Transactional
    @Query("DELETE FROM Booking b WHERE b.seat.event.id IN :eventIds")
    void deleteByEventIdIn(@Param("eventIds") List<Long> eventIds);
}
