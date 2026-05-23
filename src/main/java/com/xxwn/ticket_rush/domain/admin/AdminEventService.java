package com.xxwn.ticket_rush.domain.admin;

import com.xxwn.ticket_rush.domain.booking.BookingRepository;
import com.xxwn.ticket_rush.domain.event.Event;
import com.xxwn.ticket_rush.domain.event.EventRepository;
import com.xxwn.ticket_rush.domain.kbo.KboGameDto;
import com.xxwn.ticket_rush.domain.kbo.KboScheduleClient;
import com.xxwn.ticket_rush.domain.seat.Seat;
import com.xxwn.ticket_rush.domain.seat.SeatRepository;
import com.xxwn.ticket_rush.domain.seat.SeatService;
import com.xxwn.ticket_rush.domain.seat.SeatStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class AdminEventService {

    private static final int TOTAL_SEATS = 100;
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final KboScheduleClient kboScheduleClient;
    private final EventRepository eventRepository;
    private final SeatRepository seatRepository;
    private final BookingRepository bookingRepository;
    private final SeatService seatService;

    @Transactional
    public List<Long> importMonth(int year, int month) {
        List<KboGameDto> games = kboScheduleClient.fetch(year, month);
        List<Long> importedIds = new ArrayList<>();

        for (KboGameDto game : games) {
            LocalDate date;
            try {
                date = LocalDate.parse(game.date(), DATE_FMT);
            } catch (Exception e) {
                log.warn("날짜 파싱 실패: {}", game.date());
                continue;
            }

            LocalDateTime startTime = date.atTime(18, 30);
            LocalDateTime endTime = date.atTime(21, 30);
            String title = game.awayTeam() + " vs " + game.homeTeam() + " · " + game.venue();

            if (eventRepository.existsByTitleAndStartTime(title, startTime)) {
                log.debug("중복 스킵: {}", title);
                continue;
            }

            LocalDateTime ticketOpenTime = startTime.minusDays(7);
            Event event = eventRepository.save(Event.builder()
                    .title(title)
                    .startTime(startTime)
                    .endTime(endTime)
                    .ticketOpenTime(ticketOpenTime)
                    .homeTeam(game.homeTeam())
                    .awayTeam(game.awayTeam())
                    .build());

            List<Seat> seats = new ArrayList<>(TOTAL_SEATS);
            for (int i = 0; i < TOTAL_SEATS; i++) {
                seats.add(Seat.builder()
                        .event(event)
                        .seatNumber(String.format("%c%d", 'A' + (i / 10), i % 10 + 1))
                        .status(SeatStatus.AVAILABLE)
                        .build());
            }
            seatRepository.saveAll(seats);
            seatService.warmupSeats(event.getId());

            importedIds.add(event.getId());
            log.info("KBO 경기 등록: {} ({})", title, game.date());
        }

        log.info("KBO 임포트 완료 - year={} month={}, 신규 등록: {}건", year, month, importedIds.size());
        return importedIds;
    }

    @Transactional
    public void deletePastEvents() {
        LocalDateTime now = LocalDateTime.now(ZoneId.of("Asia/Seoul"));
        List<Long> pastIds = eventRepository.findIdsByEndTimeBefore(now);
        if (pastIds.isEmpty()) return;

        bookingRepository.deleteByEventIdIn(pastIds);
        seatRepository.deleteByEventIdIn(pastIds);
        eventRepository.deleteAllById(pastIds);
        log.info("지난 경기 삭제 완료: {}건", pastIds.size());
    }
}
