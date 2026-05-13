package com.xxwn.ticket_rush.domain.booking;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface BookingRepository extends JpaRepository<Booking, Long> {
    @Query("select count(b) from Booking b where b.seat.event.id = :eventId and b.seat.id = :seatId")
    long countByEventIdAndSeatId(@Param("eventId") Long eventId, @Param("seatId") Long seatId);

    Long deleteByUserIdAndSeatId(String userId, Long seatId);

    Optional<Booking> findByUserIdAndSeatIdAndStatus(String userId, Long seatId, BookingStatus status);
}
