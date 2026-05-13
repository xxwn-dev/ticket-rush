package com.xxwn.ticket_rush.domain.booking;

import com.xxwn.ticket_rush.domain.event.Event;
import com.xxwn.ticket_rush.domain.seat.Seat;
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

    private String userId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "event_id", nullable = false)
    private Event event;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "seat_id", unique = true, nullable = false)
    private Seat seat;

    private LocalDateTime bookedAt;

    @Enumerated(EnumType.STRING)
    private BookingStatus status;

    public void confirm() {
        this.status = BookingStatus.CONFIRMED;
    }

    public static Booking create(String userId, Seat seat){
        return Booking.builder()
                .userId(userId)
                .seat(seat)
                .event(seat.getEvent())
                .status(BookingStatus.PAYMENT_PENDING)
                .bookedAt(LocalDateTime.now())
                .build();
    }
}
