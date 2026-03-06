package com.xxwn.ticketing_app.integration;

import com.xxwn.ticketing_app.config.RabbitMQConfig;
import com.xxwn.ticketing_app.domain.booking.Booking;
import com.xxwn.ticketing_app.domain.booking.BookingMessage;
import com.xxwn.ticketing_app.domain.booking.BookingRepository;
import com.xxwn.ticketing_app.domain.concert.Concert;
import com.xxwn.ticketing_app.domain.concert.ConcertRepository;
import com.xxwn.ticketing_app.domain.seat.Seat;
import com.xxwn.ticketing_app.domain.seat.SeatRepository;
import com.xxwn.ticketing_app.domain.seat.SeatStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.*;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V2 예매 전체 흐름 통합 테스트
 * Lua Script → RabbitMQ → BookingConsumer → DB
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class BookingFlowTest {

    @LocalServerPort int port;

    @Autowired TestRestTemplate restTemplate;
    @Autowired StringRedisTemplate redisTemplate;
    @Autowired BookingRepository bookingRepository;
    @Autowired SeatRepository seatRepository;
    @Autowired ConcertRepository concertRepository;
    @Autowired RabbitTemplate rabbitTemplate;

    private String baseUrl;
    private Long concertId;
    private Long seatId;
    private String cacheKey;

    @BeforeEach
    void setUp() {
        baseUrl = "http://localhost:" + port;

        Concert concert = concertRepository.save(Concert.builder()
                .title("Flow Test Concert")
                .startTime(LocalDateTime.now().plusDays(1))
                .endTime(LocalDateTime.now().plusDays(1).plusHours(3))
                .build());
        Seat seat = seatRepository.save(Seat.builder()
                .seatNumber("B1")
                .status(SeatStatus.AVAILABLE)
                .concert(concert)
                .build());

        concertId = concert.getId();
        seatId = seat.getId();
        cacheKey = "concert:" + concertId + ":available";

        redisTemplate.delete(cacheKey);
        redisTemplate.opsForSet().add(cacheKey, seatId.toString());
        redisTemplate.opsForValue().set("active_user:1", "true", 600, java.util.concurrent.TimeUnit.SECONDS);
        redisTemplate.delete("booking:result:1");
    }

    @AfterEach
    void cleanUp() {
        bookingRepository.deleteAll();
        seatRepository.deleteAll();
        concertRepository.deleteAll();
        redisTemplate.delete(cacheKey);
        redisTemplate.delete("booking:result:1");
        redisTemplate.delete("active_user:1");
    }

    @Test
    @DisplayName("V2 예매 성공: Lua 선점 → MQ → Consumer → DB 저장 → 상태 PAYMENT_PENDING")
    void v2Booking_fullFlow() throws InterruptedException {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-USER-ID", "1");
        headers.setContentType(MediaType.APPLICATION_JSON);

        String body = String.format("{\"concertId\":%d,\"seatId\":%d}", concertId, seatId);

        ResponseEntity<String> bookingResponse = restTemplate.exchange(
                baseUrl + "/api/v2/bookings", HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class);

        assertThat(bookingResponse.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

        // RabbitMQ Consumer 비동기 처리 대기
        Thread.sleep(1000);

        // DB에 예약이 생성되어야 함
        assertThat(bookingRepository.count()).isEqualTo(1);

        // 좌석 상태가 Redis에서 제거되어야 함
        assertThat(redisTemplate.opsForSet().isMember(cacheKey, seatId.toString())).isFalse();
    }

    @Test
    @DisplayName("V2 예매: 이미 선점된 좌석은 409 Conflict 반환")
    void v2Booking_alreadyTaken() {
        // 좌석을 미리 Redis에서 제거 (선점된 상태 시뮬레이션)
        redisTemplate.opsForSet().remove(cacheKey, seatId.toString());

        HttpHeaders headers = new HttpHeaders();
        headers.set("X-USER-ID", "1");
        headers.setContentType(MediaType.APPLICATION_JSON);

        String body = String.format("{\"concertId\":%d,\"seatId\":%d}", concertId, seatId);

        ResponseEntity<String> response = restTemplate.exchange(
                baseUrl + "/api/v2/bookings", HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    @DisplayName("V2 취소: PAYMENT_PENDING 상태에서 취소 시 좌석이 복구된다")
    void v2Cancel_success() throws InterruptedException {
        // V2 예매 먼저 실행
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-USER-ID", "1");
        headers.setContentType(MediaType.APPLICATION_JSON);
        String body = String.format("{\"concertId\":%d,\"seatId\":%d}", concertId, seatId);

        restTemplate.exchange(baseUrl + "/api/v2/bookings", HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class);
        Thread.sleep(1000);

        // 취소 요청
        ResponseEntity<Void> cancelResponse = restTemplate.exchange(
                baseUrl + "/api/v2/bookings", HttpMethod.DELETE,
                new HttpEntity<>(body, headers), Void.class);

        assertThat(cancelResponse.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

        Thread.sleep(500);

        // 좌석이 다시 가용 상태로 복구되어야 함
        assertThat(redisTemplate.opsForSet().isMember(cacheKey, seatId.toString())).isTrue();
    }

    @Test
    @DisplayName("좌석 선점 만료: PAYMENT_PENDING 상태에서 만료 메시지 수신 시 좌석과 예매가 복구된다")
    void holdExpiry_releasesExpiredSeat() throws InterruptedException {
        // 좌석 예약 상태로 변경 + Redis에서 제거 (예매 직후 상태 시뮬레이션)
        Seat seat = seatRepository.findById(seatId).orElseThrow();
        seat.reserve();
        seatRepository.save(seat);
        redisTemplate.opsForSet().remove(cacheKey, seatId.toString());

        // DB에 PAYMENT_PENDING 예매 생성
        bookingRepository.save(Booking.create(1L, seat));

        // TTL 만료를 건너뛰고 DLX 큐에 직접 메시지 발행 (컨슈머 로직만 검증)
        BookingMessage expiredMessage = new BookingMessage(1L, seatId, concertId);
        rabbitTemplate.convertAndSend(
                RabbitMQConfig.HOLD_DLX,
                RabbitMQConfig.HOLD_EXPIRED_ROUTING_KEY,
                expiredMessage
        );

        // 컨슈머 비동기 처리 대기
        Thread.sleep(1000);

        // 예매가 삭제되어야 함
        assertThat(bookingRepository.count()).isEqualTo(0);

        // 좌석이 Redis Set에 복구되어야 함
        assertThat(redisTemplate.opsForSet().isMember(cacheKey, seatId.toString())).isTrue();

        // 좌석 DB 상태가 AVAILABLE로 복구되어야 함
        assertThat(seatRepository.findById(seatId).orElseThrow().getStatus())
                .isEqualTo(SeatStatus.AVAILABLE);
    }

    @Test
    @DisplayName("좌석 선점 만료: 이미 결제 완료된 예매는 만료 메시지 수신 시 복구되지 않는다")
    void holdExpiry_ignoresConfirmedBooking() throws InterruptedException {
        // 좌석 예약 + CONFIRMED 예매 생성
        Seat seat = seatRepository.findById(seatId).orElseThrow();
        seat.reserve();
        seatRepository.save(seat);
        redisTemplate.opsForSet().remove(cacheKey, seatId.toString());

        Booking booking = bookingRepository.save(Booking.create(1L, seat));
        booking.confirm();
        bookingRepository.save(booking);

        // 만료 메시지 발행
        BookingMessage expiredMessage = new BookingMessage(1L, seatId, concertId);
        rabbitTemplate.convertAndSend(
                RabbitMQConfig.HOLD_DLX,
                RabbitMQConfig.HOLD_EXPIRED_ROUTING_KEY,
                expiredMessage
        );

        Thread.sleep(1000);

        // CONFIRMED 예매는 삭제되지 않아야 함
        assertThat(bookingRepository.count()).isEqualTo(1);

        // 좌석이 Redis Set에 복구되지 않아야 함
        assertThat(redisTemplate.opsForSet().isMember(cacheKey, seatId.toString())).isFalse();
    }

    @Test
    @DisplayName("인터셉터: active_user 토큰 없는 유저는 V2 예매 요청이 403으로 차단된다")
    void interceptor_blocksUnauthorizedBooking() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-USER-ID", "999"); // active_user:999 없음
        headers.setContentType(MediaType.APPLICATION_JSON);

        String body = String.format("{\"concertId\":%d,\"seatId\":%d}", concertId, seatId);

        ResponseEntity<String> response = restTemplate.exchange(
                baseUrl + "/api/v2/bookings", HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }
}
