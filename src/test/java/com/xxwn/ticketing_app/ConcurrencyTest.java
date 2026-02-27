package com.xxwn.ticketing_app;

import com.xxwn.ticketing_app.domain.booking.BookingMessage;
import com.xxwn.ticketing_app.domain.booking.BookingService;
import com.xxwn.ticketing_app.domain.booking.BookingRepository;
import com.xxwn.ticketing_app.domain.booking.RedisLockFacade;
import com.xxwn.ticketing_app.domain.concert.Concert;
import com.xxwn.ticketing_app.domain.concert.ConcertRepository;
import com.xxwn.ticketing_app.domain.seat.Seat;
import com.xxwn.ticketing_app.domain.seat.SeatRepository;
import com.xxwn.ticketing_app.domain.seat.SeatStatus;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
//@ActiveProfiles("test")
public class ConcurrencyTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private RedisLockFacade redisLockFacade;

    @Autowired
    private BookingService bookingService;

    @Autowired
    private ConcertRepository concertRepository;

    @Autowired
    private SeatRepository seatRepository;

    @Autowired
    private BookingRepository bookingRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @BeforeEach
    void cleanUp() {
        String key = "concert:1:available";
        redisTemplate.delete(key);
        redisTemplate.opsForSet().add(key, "10");

//        transactionTemplate.execute(status -> {
//            // 1. 외래 키 제약 조건 비활성화
//            entityManager.createNativeQuery("SET FOREIGN_KEY_CHECKS = 0").executeUpdate();
//
//            // 2. 테이블 비우기 (테이블명은 실제 DB와 일치해야 합니다)
//            entityManager.createNativeQuery("TRUNCATE TABLE booking").executeUpdate();
//            entityManager.createNativeQuery("TRUNCATE TABLE seat").executeUpdate();
//            entityManager.createNativeQuery("TRUNCATE TABLE concert").executeUpdate();
//
//            // 3. 외래 키 제약 조건 다시 활성화
//            entityManager.createNativeQuery("SET FOREIGN_KEY_CHECKS = 1").executeUpdate();
//            return null;
//        });
    }
    @Test
    @DisplayName("100명이 동시에 한 좌석 예매 1명 성공 99명 실패")
    void concurrencyTest() throws InterruptedException {
//        Concert concert = concertRepository.save(Concert.builder()
//                .title("Concert C")
//                .startTime(LocalDateTime.now().plusDays(1))
//                .endTime(LocalDateTime.now().plusDays(1).plusHours(3))
//                .build());
//        Seat seat = seatRepository.save(Seat.builder()
//                .seatNumber("A1")
//                .status(SeatStatus.AVAILABLE)
//                .concert(concert)
//                .build());

        int threadCount = 200;
        ExecutorService executorService = Executors.newFixedThreadPool(32);
        CountDownLatch latch = new CountDownLatch(threadCount);

        Long concertId = 1L;
        Long seatId = 10L;
        for (int i = 0; i < threadCount; i++) {
            long userId = (long) i;
            executorService.submit(() -> {
                try {
                    // 수정된 Facade 호출 (락 -> 재고차감 -> DB저장)
                    redisLockFacade.createBookingWithLock(userId, seatId, concertId);
                } catch (Exception e) {
                    // 실패한 99명의 예외는 로그로 확인 (또는 무시)
                    System.out.println("예매 실패 사유: " + e.getMessage());
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(); // 모든 쓰레드가 종료될 때까지 대기

        // --- 검증 ---

        // 1. DB에 저장된 예약 정보는 반드시 1개여야 함
        long bookingCount = bookingRepository.count();
        System.out.println("최종 예약 완료 건수: " + bookingCount);
        assertThat(bookingCount).isEqualTo(1);

        // 2. Redis의 해당 좌석 재고는 비어있어야 함 (Set에서 삭제되었으므로)
        Boolean isAvailable = redisTemplate.opsForSet().isMember("concert:1:available", "1");
        assertThat(isAvailable).isFalse();
    }
}
