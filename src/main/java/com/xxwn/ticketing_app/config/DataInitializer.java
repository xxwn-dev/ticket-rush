package com.xxwn.ticketing_app.config;

import com.xxwn.ticketing_app.domain.booking.BookingRepository;
import com.xxwn.ticketing_app.domain.concert.Concert;
import com.xxwn.ticketing_app.domain.concert.ConcertRepository;
import com.xxwn.ticketing_app.domain.seat.Seat;
import com.xxwn.ticketing_app.domain.seat.SeatRepository;
import com.xxwn.ticketing_app.domain.seat.SeatService;
import com.xxwn.ticketing_app.domain.seat.SeatStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Component
@Profile("!test")
@RequiredArgsConstructor
@Slf4j
public class DataInitializer {

    private final ConcertRepository concertRepository;
    private final SeatRepository seatRepository;
    private final BookingRepository bookingRepository;
    private final SeatService seatService;

    @EventListener(ApplicationReadyEvent.class)
    public void init(){
        log.info("테스트 데이터 초기화 및 웜업 시작...");

        bookingRepository.deleteAll();
        seatRepository.deleteAll();
        concertRepository.deleteAll();

        Concert sample = Concert.builder()
                .title("Concert A")
                .startTime(LocalDateTime.of(2026, 6, 1, 18, 0))
                .endTime(LocalDateTime.of(2026, 6, 1, 21, 0))
                .build();
        Concert savedConcert = concertRepository.save(sample);

        List<Seat> seats = new ArrayList<>();
        for(int i=0; i<2000; i++){
            seats.add(Seat.builder()
                    .concert(sample)
                    .seatNumber("A" +i)
                    .status(SeatStatus.AVAILABLE)
                    .build());
        }
        seatRepository.saveAll(seats);

        seatService.warmupSeats(savedConcert.getId());

        log.info("초기화 및 웜업 완료");
    }
}
