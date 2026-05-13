package com.xxwn.ticket_rush.domain.admin;

import com.xxwn.ticket_rush.domain.booking.BookingRepository;
import com.xxwn.ticket_rush.domain.event.Event;
import com.xxwn.ticket_rush.domain.event.EventRepository;
import com.xxwn.ticket_rush.domain.seat.Seat;
import com.xxwn.ticket_rush.domain.seat.SeatRepository;
import com.xxwn.ticket_rush.domain.seat.SeatService;
import com.xxwn.ticket_rush.domain.seat.SeatStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class DataResetService {

    private static final int TOTAL_SEATS = 100;

    private final EventRepository eventRepository;
    private final SeatRepository seatRepository;
    private final BookingRepository bookingRepository;
    private final SeatService seatService;
    private final StringRedisTemplate redisTemplate;

    public Long reset() {
        log.info("데이터 초기화 시작...");

        bookingRepository.deleteAll();
        seatRepository.deleteAll();
        eventRepository.deleteAll();

        redisTemplate.delete("event:waiting_queue");

        Event event = eventRepository.save(Event.builder()
                .title("LG vs KIA · 잠실")
                .startTime(LocalDateTime.of(2026, 6, 1, 18, 0))
                .endTime(LocalDateTime.of(2026, 6, 1, 21, 0))
                .build());

        List<Seat> seats = new ArrayList<>(TOTAL_SEATS);
        for (int i = 0; i < TOTAL_SEATS; i++) {
            seats.add(Seat.builder()
                    .event(event)
                    .seatNumber(String.format("%c%d", 'A' + (i / 100), i % 100))
                    .status(SeatStatus.AVAILABLE)
                    .build());
        }
        seatRepository.saveAll(seats);
        seatService.warmupSeats(event.getId());

        log.info("초기화 완료 - eventId: {}, seats: {}", event.getId(), TOTAL_SEATS);
        return event.getId();
    }
}
