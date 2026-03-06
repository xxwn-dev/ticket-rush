package com.xxwn.ticketing_app.domain.booking;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface BookingRepository extends JpaRepository<Booking, Long> {
    @Query("select count(b) from Booking b where b.seat.concert.id = :concertId and b.seat.id = :seatId")
    long countByConcertIdAndSeatId(@Param("concertId") Long concertId, @Param("seatId") Long seatId);

    Long deleteByUserIdAndSeatId(Long userId, Long seatId);

    Optional<Booking> findByUserIdAndSeatIdAndStatus(Long userId, Long seatId, BookingStatus status);
}
