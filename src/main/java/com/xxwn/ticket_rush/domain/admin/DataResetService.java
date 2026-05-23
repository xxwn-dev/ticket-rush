package com.xxwn.ticket_rush.domain.admin;

import com.xxwn.ticket_rush.domain.booking.BookingRepository;
import com.xxwn.ticket_rush.domain.event.EventRepository;
import com.xxwn.ticket_rush.domain.seat.SeatRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;

@Service
@RequiredArgsConstructor
@Slf4j
public class DataResetService {

    private final EventRepository eventRepository;
    private final SeatRepository seatRepository;
    private final BookingRepository bookingRepository;
    private final StringRedisTemplate redisTemplate;
    private final AdminEventService adminEventService;

    public void reset() {
        log.info("데이터 초기화 시작...");

        bookingRepository.deleteAll();
        seatRepository.deleteAll();
        eventRepository.deleteAll();

        redisTemplate.delete("event:waiting_queue");
        deleteByPattern("active_user:*");
        deleteByPattern("booking:result:*");
        deleteByPattern("payment:order:*");

        try {
            LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
            adminEventService.importMonth(today.getYear(), today.getMonthValue());
            LocalDate next = today.plusMonths(1);
            adminEventService.importMonth(next.getYear(), next.getMonthValue());
        } catch (Exception e) {
            log.warn("KBO 임포트 실패, 빈 이벤트 목록으로 시작: {}", e.getMessage());
        }

        log.info("초기화 완료");
    }

    private void deleteByPattern(String pattern) {
        var keys = redisTemplate.keys(pattern);
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }
}
