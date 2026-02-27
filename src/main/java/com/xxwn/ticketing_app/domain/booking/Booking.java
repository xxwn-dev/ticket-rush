package com.xxwn.ticketing_app.domain.booking;

import com.xxwn.ticketing_app.domain.concert.Concert;
import com.xxwn.ticketing_app.domain.seat.Seat;
import jakarta.persistence.*;
import lombok.*;

import java.awt.print.Book;
import java.time.LocalDateTime;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class Booking {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long userId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "concert_id", nullable = false)
    private Concert concert;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "seat_id", unique = true, nullable = false)
    private Seat seat;

    private LocalDateTime bookedAt;

    @Enumerated(EnumType.STRING)
    private BookingStatus status;

    public void confirm() {
        this.status = BookingStatus.CONFIRMED;
    }

    public static Booking create(Long userId, Seat seat){
        return Booking.builder()
                .userId(userId)
                .seat(seat)
                .concert(seat.getConcert())
                .status(BookingStatus.PAYMENT_PENDING)
                .bookedAt(LocalDateTime.now())
                .build();
    }
}
