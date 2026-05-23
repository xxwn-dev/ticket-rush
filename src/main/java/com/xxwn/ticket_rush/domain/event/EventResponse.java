package com.xxwn.ticket_rush.domain.event;

import java.time.LocalDateTime;

public record EventResponse(
        Long id,
        String title,
        LocalDateTime openAt,
        long totalSeats,
        long availableSeats,
        String status,
        LocalDateTime ticketOpenAt,
        boolean isTicketOpen,
        String homeTeam,
        String awayTeam
) {
    public static EventResponse of(Event event, long totalSeats, long availableSeats) {
        LocalDateTime now = LocalDateTime.now();
        String status = event.isReservable(now)
                ? (availableSeats > 0 ? "OPEN" : "SOLD_OUT")
                : "CLOSED";
        return new EventResponse(
                event.getId(),
                event.getTitle(),
                event.getStartTime(),
                totalSeats,
                availableSeats,
                status,
                event.getTicketOpenTime(),
                event.getTicketOpenTime().isBefore(now),
                event.getHomeTeam(),
                event.getAwayTeam()
        );
    }
}
