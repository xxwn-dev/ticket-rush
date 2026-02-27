package com.xxwn.ticketing_app;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.*;
import org.springframework.test.context.ActiveProfiles;

import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sentinel + SSE 통합 테스트
 *
 * [test 프로파일 동작 방식]
 * application-test.yaml 이 Sentinel 설정을 단일 Redis 직접 연결로 덮어씁니다.
 * 따라서 로컬에서 redis-master 만 띄운 상태로 테스트 실행 가능합니다:
 *   docker-compose up -d mysql redis-master rabbitmq
 *   ./gradlew test
 *
 * [실제 Sentinel Failover 테스트는 별도 수동 절차 참고]
 * → 파일 하단 manualFailoverGuide() 참고
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class SentinelIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private StringRedisTemplate redisTemplate;

    private String baseUrl;

    @BeforeEach
    void setUp() {
        baseUrl = "http://localhost:" + port;

        // 각 테스트 전 대기열 및 활성화 키 초기화
        redisTemplate.delete("concert:waiting_queue");
        Set<String> activeKeys = redisTemplate.keys("active_user:*");
        if (activeKeys != null && !activeKeys.isEmpty()) {
            redisTemplate.delete(activeKeys);
        }
        Set<String> resultKeys = redisTemplate.keys("booking:result:*");
        if (resultKeys != null && !resultKeys.isEmpty()) {
            redisTemplate.delete(resultKeys);
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // 1. Redis 연결 확인 (Sentinel → 단일 Redis 전환 후 정상 동작 확인)
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Redis 연결 정상 확인 — SET/GET/DEL 기본 동작")
    void redisConnectionTest() {
        String key = "test:sentinel:ping";
        redisTemplate.opsForValue().set(key, "pong");

        String value = redisTemplate.opsForValue().get(key);
        assertThat(value).isEqualTo("pong");

        redisTemplate.delete(key);
        assertThat(redisTemplate.hasKey(key)).isFalse();
    }

    @Test
    @DisplayName("Redis TTL 정합성 확인 — active_user 토큰 600초 TTL")
    void redisTtlTest() throws Exception {
        String key = "active_user:9999";
        redisTemplate.opsForValue().set(key, "true", 600, TimeUnit.SECONDS);

        Long ttl = redisTemplate.getExpire(key, TimeUnit.SECONDS);

        assertThat(ttl).isGreaterThan(590L);   // 600초에서 약간 차감될 수 있음
        assertThat(ttl).isLessThanOrEqualTo(600L);
    }

    // ─────────────────────────────────────────────────────────────────────
    // 2. SSE 즉시 응답 테스트 (active_user: 키 존재 시 즉각 GO_BOOKING 반환)
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("SSE 즉시 응답 — 이미 활성화된 유저는 즉각 GO_BOOKING 수신")
    void sseImmediateResponseTest() {
        Long userId = 101L;
        // 스케줄러가 이미 통과시킨 상황 시뮬레이션
        redisTemplate.opsForValue().set("active_user:" + userId, "true", 600, TimeUnit.SECONDS);

        HttpHeaders headers = new HttpHeaders();
        headers.set("X-USER-ID", userId.toString());
        headers.setAccept(java.util.List.of(MediaType.TEXT_EVENT_STREAM));

        ResponseEntity<String> response = restTemplate.exchange(
                baseUrl + "/api/subscribe",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("GO_BOOKING");
    }

    // ─────────────────────────────────────────────────────────────────────
    // 3. SSE 대기 → 스케줄러 입장 신호 수신 테스트
    //    실제 Pub/Sub 흐름: Queue join → Scheduler promote → Pub/Sub → SSE
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("SSE 대기 → 대기열 진입 → 스케줄러 프로모션 → GO_BOOKING 수신")
    void sseWaitAndReceiveViaSchedulerTest() throws Exception {
        Long userId = 202L;

        HttpHeaders headers = new HttpHeaders();
        headers.set("X-USER-ID", userId.toString());
        headers.setAccept(java.util.List.of(MediaType.TEXT_EVENT_STREAM));
        headers.setContentType(MediaType.APPLICATION_JSON);

        // SSE 구독을 별도 스레드에서 시작 (서버가 신호를 보내야 HTTP 응답이 끝남)
        CompletableFuture<String> sseFuture = CompletableFuture.supplyAsync(() ->
                restTemplate.exchange(
                        baseUrl + "/api/subscribe",
                        HttpMethod.GET,
                        new HttpEntity<>(headers),
                        String.class
                ).getBody()
        );

        // SSE 연결이 먼저 맺어지도록 잠시 대기 후 대기열 진입
        Thread.sleep(150);
        restTemplate.exchange(
                baseUrl + "/api/queue/join",
                HttpMethod.POST,
                new HttpEntity<>(headers),
                String.class
        );

        // 스케줄러가 500ms 내에 프로모션 → Pub/Sub → SSE 전송
        // 최대 3초 대기 (스케줄러 딜레이 + 네트워크 여유)
        String sseBody = sseFuture.get(3, TimeUnit.SECONDS);

        assertThat(sseBody).contains("GO_BOOKING");

        // 프로모션 후 active_user: 토큰이 생성되었는지 확인
        assertThat(redisTemplate.hasKey("active_user:" + userId)).isTrue();
        // 대기열에서 제거되었는지 확인
        Double rank = redisTemplate.opsForZSet().score("concert:waiting_queue", userId.toString());
        assertThat(rank).isNull();
    }

    // ─────────────────────────────────────────────────────────────────────
    // 4. 대기열 순번 정합성 테스트
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("대기열 순번 정합성 — 먼저 진입한 유저가 낮은 score(먼저 처리)")
    void queueOrderTest() throws InterruptedException {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        for (long userId = 1; userId <= 5; userId++) {
            headers.set("X-USER-ID", String.valueOf(userId));
            restTemplate.exchange(
                    baseUrl + "/api/queue/join",
                    HttpMethod.POST,
                    new HttpEntity<>(headers),
                    String.class
            );
            Thread.sleep(10); // score는 System.currentTimeMillis() 기반
        }

        // ZSet은 score 오름차순 → 먼저 진입한 유저가 앞에
        Set<String> orderedUsers = redisTemplate.opsForZSet()
                .range("concert:waiting_queue", 0, -1);

        assertThat(orderedUsers).hasSize(5);
        assertThat(orderedUsers.iterator().next()).isEqualTo("1"); // userId=1이 가장 먼저
    }

    // ─────────────────────────────────────────────────────────────────────
    // 5. 인터셉터 보안 — active_user: 없으면 예약 API 차단
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("인터셉터 보안 — 대기열 미통과 유저는 예약 요청이 403으로 차단")
    void interceptorBlocksUnauthorizedUser() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-USER-ID", "9999");  // active_user:9999 없음
        headers.setContentType(MediaType.APPLICATION_JSON);

        String body = "{\"concertId\":1,\"seatId\":1}";
        ResponseEntity<String> response = restTemplate.exchange(
                baseUrl + "/api/v2/bookings",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ─────────────────────────────────────────────────────────────────────
    // 6. [수동 Failover 가이드] — JUnit으로 자동화 불가, 아래 절차로 수동 테스트
    // ─────────────────────────────────────────────────────────────────────
    //
    //  Sentinel Failover 수동 테스트 절차:
    //
    //  사전 준비:
    //    docker-compose up -d   (Sentinel 포함 전체 스택)
    //    docker-compose --profile full up -d app   (Spring Boot도 Docker에서 실행)
    //
    //  Failover 시뮬레이션:
    //    1. 마스터 컨테이너 강제 종료:
    //       docker stop ticket-redis-master
    //
    //    2. Sentinel이 5초(down-after-milliseconds) 후 failover 감지
    //       로그 확인: docker logs ticket-redis-sentinel-1
    //       → "+switch-master mymaster ... redis-replica 6379" 메시지 확인
    //
    //    3. 약 10초 후 새 마스터(redis-replica)로 자동 전환
    //       Spring Boot 앱이 Lettuce를 통해 자동 재연결
    //
    //    4. 예약 요청 정상 처리 확인:
    //       curl -X POST http://localhost:8080/api/queue/join -H "X-USER-ID: 1"
    //
    //    5. 이전 마스터 복구 후 replica로 합류 확인:
    //       docker start ticket-redis-master
    //       redis-cli -p 26379 sentinel slaves mymaster
}
