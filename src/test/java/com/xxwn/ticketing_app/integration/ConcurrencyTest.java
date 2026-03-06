package com.xxwn.ticketing_app.integration;

import com.xxwn.ticketing_app.domain.booking.BookingRepository;
import com.xxwn.ticketing_app.domain.concert.Concert;
import com.xxwn.ticketing_app.domain.concert.ConcertRepository;
import com.xxwn.ticketing_app.domain.booking.RedisLockFacade;
import com.xxwn.ticketing_app.domain.seat.Seat;
import com.xxwn.ticketing_app.domain.seat.SeatRepository;
import com.xxwn.ticketing_app.domain.seat.SeatStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 동시성 테스트 (V1 — Redisson 분산 락)
 * 200 스레드가 같은 좌석을 동시에 예매 시도 → 정확히 1건만 성공해야 한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ConcurrencyTest {

    @Autowired RedisLockFacade redisLockFacade;
    @Autowired BookingRepository bookingRepository;
    @Autowired SeatRepository seatRepository;
    @Autowired ConcertRepository concertRepository;
    @Autowired StringRedisTemplate redisTemplate;

    private Long concertId;
    private Long seatId;
    private String cacheKey;

    @BeforeEach
    void setUp() {
        bookingRepository.deleteAll();

        Concert concert = concertRepository.save(Concert.builder()
                .title("Concurrency Test Concert")
                .startTime(LocalDateTime.now().plusDays(1))
                .endTime(LocalDateTime.now().plusDays(1).plusHours(3))
                .build());
        Seat seat = seatRepository.save(Seat.builder()
                .seatNumber("C1")
                .status(SeatStatus.AVAILABLE)
                .concert(concert)
                .build());

        concertId = concert.getId();
        seatId = seat.getId();
        cacheKey = "concert:" + concertId + ":available";

        redisTemplate.delete(cacheKey);
        redisTemplate.opsForSet().add(cacheKey, seatId.toString());
    }

    @AfterEach
    void cleanUp() {
        bookingRepository.deleteAll();
        seatRepository.deleteAll();
        concertRepository.deleteAll();
        redisTemplate.delete(cacheKey);
    }

    @Test
    @DisplayName("200 스레드 동시 예매 시도 → DB 예약 1건, 나머지 거부")
    void concurrentBooking_exactlyOneSuccess() throws InterruptedException {
        int threadCount = 200;
        ExecutorService executorService = Executors.newFixedThreadPool(32);
        CountDownLatch latch = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            long userId = i;
            executorService.submit(() -> {
                try {
                    redisLockFacade.createBookingWithLock(userId, seatId, concertId);
                } catch (Exception ignored) {
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executorService.shutdown();

        // DB에 예약이 정확히 1건이어야 함
        assertThat(bookingRepository.count()).isEqualTo(1);

        // Redis Set에서 해당 좌석이 제거되어야 함
        assertThat(redisTemplate.opsForSet().isMember(cacheKey, seatId.toString())).isFalse();
    }
}
