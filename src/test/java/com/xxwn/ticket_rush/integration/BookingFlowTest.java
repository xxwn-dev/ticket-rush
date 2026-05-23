package com.xxwn.ticket_rush.integration;

import com.xxwn.ticket_rush.config.RabbitMQConfig;
import com.xxwn.ticket_rush.domain.booking.Booking;
import com.xxwn.ticket_rush.domain.booking.BookingMessage;
import com.xxwn.ticket_rush.domain.booking.BookingRepository;
import com.xxwn.ticket_rush.domain.event.Event;
import com.xxwn.ticket_rush.domain.event.EventRepository;
import com.xxwn.ticket_rush.domain.seat.Seat;
import com.xxwn.ticket_rush.domain.seat.SeatRepository;
import com.xxwn.ticket_rush.domain.seat.SeatStatus;
import com.xxwn.ticket_rush.support.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.*;

import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V2 예매 전체 흐름 통합 테스트
 * Lua Script → RabbitMQ → BookingConsumer → DB
 */
class BookingFlowTest extends AbstractIntegrationTest {

    @LocalServerPort int port;

    @Autowired TestRestTemplate restTemplate;
    @Autowired StringRedisTemplate redisTemplate;
    @Autowired BookingRepository bookingRepository;
    @Autowired SeatRepository seatRepository;
    @Autowired EventRepository concertRepository;
    @Autowired RabbitTemplate rabbitTemplate;

    private String baseUrl;
    private Long eventId;
    private Long seatId;
    private String cacheKey;

    @BeforeEach
    void setUp() {
        baseUrl = "http://localhost:" + port;

        Event event = concertRepository.save(Event.builder()
                .title("Flow Test Event")
                .startTime(LocalDateTime.now().plusDays(1))
                .endTime(LocalDateTime.now().plusDays(1).plusHours(3))
                .ticketOpenTime(LocalDateTime.now().minusDays(6))
                .homeTeam("LG 트윈스")
                .awayTeam("KIA 타이거즈")
                .build());
        Seat seat = seatRepository.save(Seat.builder()
                .seatNumber("B1")
                .status(SeatStatus.AVAILABLE)
                .event(event)
                .build());

        eventId = event.getId();
        seatId = seat.getId();
        cacheKey = "event:" + eventId + ":available";

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
    @DisplayName("V2 예매 성공: Lua 선점 → Redis PAYMENT_PENDING 상태 → orderId 반환 (DB 저장은 결제 확인 후)")
    void v2Booking_fullFlow() throws InterruptedException {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-USER-ID", "1");
        headers.setContentType(MediaType.APPLICATION_JSON);

        String body = String.format("{\"eventId\":%d}", eventId);

        ResponseEntity<String> bookingResponse = restTemplate.exchange(
                baseUrl + "/api/v2/bookings", HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class);

        assertThat(bookingResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(bookingResponse.getBody()).contains("orderId");
        assertThat(bookingResponse.getBody()).contains("amount");

        // 좌석이 Redis에서 선점되어야 함
        assertThat(redisTemplate.opsForSet().isMember(cacheKey, seatId.toString())).isFalse();

        // 결제 확인 전이므로 DB에 예약 레코드 없음
        assertThat(bookingRepository.count()).isEqualTo(0);

        // booking:result 상태가 PAYMENT_PENDING이어야 함
        String resultKey = "booking:result:1";
        assertThat(redisTemplate.opsForValue().get(resultKey)).isEqualTo("PAYMENT_PENDING");
    }

    @Test
    @DisplayName("V2 예매: 이미 선점된 좌석은 409 Conflict 반환")
    void v2Booking_alreadyTaken() {
        // 좌석을 미리 Redis에서 제거 (선점된 상태 시뮬레이션)
        redisTemplate.opsForSet().remove(cacheKey, seatId.toString());

        HttpHeaders headers = new HttpHeaders();
        headers.set("X-USER-ID", "1");
        headers.setContentType(MediaType.APPLICATION_JSON);

        String body = String.format("{\"eventId\":%d,\"seatId\":%d}", eventId, seatId);

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
        String body = String.format("{\"eventId\":%d,\"seatId\":%d}", eventId, seatId);

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
        bookingRepository.save(Booking.create("user-test-1", seat));

        // TTL 만료를 건너뛰고 DLX 큐에 직접 메시지 발행 (컨슈머 로직만 검증)
        BookingMessage expiredMessage = new BookingMessage("user-test-1", seatId, eventId);
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

        Booking booking = bookingRepository.save(Booking.create("user-test-1", seat));
        booking.confirm();
        bookingRepository.save(booking);

        // 만료 메시지 발행
        BookingMessage expiredMessage = new BookingMessage("user-test-1", seatId, eventId);
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

        String body = String.format("{\"eventId\":%d,\"seatId\":%d}", eventId, seatId);

        ResponseEntity<String> response = restTemplate.exchange(
                baseUrl + "/api/v2/bookings", HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }
}
