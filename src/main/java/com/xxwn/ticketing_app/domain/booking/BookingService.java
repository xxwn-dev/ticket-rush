package com.xxwn.ticketing_app.domain.booking;

import com.xxwn.ticketing_app.domain.seat.Seat;
import com.xxwn.ticketing_app.domain.seat.SeatRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class BookingService {
    private final BookingRepository bookingRepository;
    private final SeatRepository seatRepository;
    private final StringRedisTemplate redisTemplate;

    @Transactional
    public Long createBooking(Long userId, Long seatId, Long concertId){
        Seat seat = seatRepository.findByIdAndConcertId(seatId, concertId)
                .orElseThrow(() -> new IllegalArgumentException("Wrong Seat id"));

        seat.reserve();

        Booking booking = Booking.create(userId, seat);
        return bookingRepository.save(booking).getId();
    }

    @Transactional
    public void processV2Booking(Long userId, Long seatId, Long concertId) {
        // 1. DB 좌석 상태 변경 및 저장
        Seat seat = seatRepository.findByIdAndConcertId(seatId, concertId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 좌석입니다."));

        seat.reserve(); // 좌석 상태 RESERVED 변경
        bookingRepository.save(Booking.create(userId, seat));

        // 2. 성공 상태를 Redis에 즉시 기록 (TTL 10분 — active_user: 토큰 만료와 일치)
        String resultKey = "booking:result:" + userId;
        redisTemplate.opsForValue().set(resultKey, "PAYMENT_PENDING", 600, java.util.concurrent.TimeUnit.SECONDS);
    }

    @Transactional
    public void processCancel(Long userId, Long seatId, Long concertId){
        Seat seat = seatRepository.findByIdAndConcertId(seatId, concertId).orElseThrow(() -> new EntityNotFoundException("좌석을 찾을 수 없습니다."));
        Long delCounts = bookingRepository.deleteByUserIdAndSeatId(userId,seatId);
        if(delCounts == 0){
            log.warn("Already cancelled or not exist");
            return;
        }
        seat.cancel();
        redisTemplate.opsForValue().set("booking:result:" + userId, "CANCELLED", 600, java.util.concurrent.TimeUnit.SECONDS);
    }

}
