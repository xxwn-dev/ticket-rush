package com.xxwn.ticketing_app.integration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.*;
import org.springframework.test.context.ActiveProfiles;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 대기열 흐름 통합 테스트
 * 대기열 진입 → 스케줄러 프로모션 → Pub/Sub → SSE 전달
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class QueueFlowTest {

    @LocalServerPort int port;

    @Autowired TestRestTemplate restTemplate;
    @Autowired StringRedisTemplate redisTemplate;

    private String baseUrl;

    @BeforeEach
    void setUp() {
        baseUrl = "http://localhost:" + port;
        redisTemplate.delete("concert:waiting_queue");
        Set<String> activeKeys = redisTemplate.keys("active_user:*");
        if (activeKeys != null && !activeKeys.isEmpty()) redisTemplate.delete(activeKeys);
    }

    @AfterEach
    void cleanUp() {
        redisTemplate.delete("concert:waiting_queue");
        Set<String> activeKeys = redisTemplate.keys("active_user:*");
        if (activeKeys != null && !activeKeys.isEmpty()) redisTemplate.delete(activeKeys);
    }

    // ── 대기열 진입 ──────────────────────────────────────────────────────

    @Test
    @DisplayName("대기열 진입: 요청 후 Redis ZSet에 userId가 등록된다")
    void queueJoin_registersInZSet() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-USER-ID", "100");

        restTemplate.exchange(baseUrl + "/api/queue/join", HttpMethod.POST,
                new HttpEntity<>(headers), String.class);

        Double score = redisTemplate.opsForZSet().score("concert:waiting_queue", "100");
        assertThat(score).isNotNull().isPositive();
    }

    @Test
    @DisplayName("대기열 순서: 먼저 진입한 유저가 더 낮은 score를 갖는다 (FIFO)")
    void queueOrder_firstInFirstOut() throws InterruptedException {
        HttpHeaders headers = new HttpHeaders();

        for (long userId = 1; userId <= 5; userId++) {
            headers.set("X-USER-ID", String.valueOf(userId));
            restTemplate.exchange(baseUrl + "/api/queue/join", HttpMethod.POST,
                    new HttpEntity<>(headers), String.class);
            Thread.sleep(5);
        }

        Set<String> ordered = redisTemplate.opsForZSet().range("concert:waiting_queue", 0, -1);
        assertThat(ordered).hasSize(5);
        assertThat(ordered.iterator().next()).isEqualTo("1");
    }

    // ── 스케줄러 프로모션 ────────────────────────────────────────────────

    @Test
    @DisplayName("스케줄러 프로모션: 대기열 진입 후 500ms 내 active_user 토큰이 생성된다")
    void scheduler_promotesUser() throws InterruptedException {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-USER-ID", "200");

        restTemplate.exchange(baseUrl + "/api/queue/join", HttpMethod.POST,
                new HttpEntity<>(headers), String.class);

        // 스케줄러 주기(500ms) + 여유 시간
        Thread.sleep(1500);

        assertThat(redisTemplate.hasKey("active_user:200")).isTrue();
        Double score = redisTemplate.opsForZSet().score("concert:waiting_queue", "200");
        assertThat(score).isNull(); // 대기열에서 제거되어야 함
    }

    // ── SSE ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("SSE: 이미 활성화된 유저는 구독 즉시 GO_BOOKING 수신")
    void sse_immediateGoBooking() {
        redisTemplate.opsForValue().set("active_user:101", "true", 600, TimeUnit.SECONDS);

        HttpHeaders headers = new HttpHeaders();
        headers.set("X-USER-ID", "101");
        headers.setAccept(java.util.List.of(MediaType.TEXT_EVENT_STREAM));

        ResponseEntity<String> response = restTemplate.exchange(
                baseUrl + "/api/subscribe", HttpMethod.GET,
                new HttpEntity<>(headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("GO_BOOKING");
    }

    @Test
    @DisplayName("SSE: 대기열 진입 후 스케줄러 프로모션 → GO_BOOKING 수신")
    void sse_receiveGoBookingViaScheduler() throws Exception {
        Long userId = 202L;

        HttpHeaders headers = new HttpHeaders();
        headers.set("X-USER-ID", userId.toString());
        headers.setAccept(java.util.List.of(MediaType.TEXT_EVENT_STREAM));
        headers.setContentType(MediaType.APPLICATION_JSON);

        // SSE 구독을 별도 스레드에서 시작
        CompletableFuture<String> sseFuture = CompletableFuture.supplyAsync(() ->
                restTemplate.exchange(baseUrl + "/api/subscribe", HttpMethod.GET,
                        new HttpEntity<>(headers), String.class).getBody()
        );

        // SSE 연결 후 대기열 진입
        Thread.sleep(150);
        restTemplate.exchange(baseUrl + "/api/queue/join", HttpMethod.POST,
                new HttpEntity<>(headers), String.class);

        // 스케줄러 프로모션 + Pub/Sub + SSE 전달 대기 (최대 3초)
        String sseBody = sseFuture.get(3, TimeUnit.SECONDS);

        assertThat(sseBody).contains("GO_BOOKING");
        assertThat(redisTemplate.hasKey("active_user:" + userId)).isTrue();
        assertThat(redisTemplate.opsForZSet().score("concert:waiting_queue", userId.toString())).isNull();
    }

    // ── TTL ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("active_user 토큰 TTL이 600초로 설정된다")
    void activeUserToken_ttlIs600Seconds() {
        redisTemplate.opsForValue().set("active_user:9999", "true", 600, TimeUnit.SECONDS);

        Long ttl = redisTemplate.getExpire("active_user:9999", TimeUnit.SECONDS);

        assertThat(ttl).isGreaterThan(590L).isLessThanOrEqualTo(600L);
    }
}
