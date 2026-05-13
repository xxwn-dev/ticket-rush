package com.xxwn.ticket_rush.integration;

import com.xxwn.ticket_rush.support.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Redis 핵심 연산 통합 테스트
 * - Lua Script (RESERVE, CANCEL) 원자성 검증
 * - ZSet 순서 보장 검증
 * - TTL 정합성 검증
 */
class RedisOperationTest extends AbstractIntegrationTest {

    @Autowired StringRedisTemplate redisTemplate;

    private static final String AVAILABLE_KEY = "test:concert:1:available";
    private static final String RESULT_KEY = "test:booking:result:1";
    private static final String WAITING_KEY = "test:concert:waiting_queue";

    private static final String RESERVE_LUA =
            "if redis.call('EXISTS', KEYS[2]) == 1 then return 0 end " +
            "if redis.call('SREM', KEYS[1], ARGV[1]) == 1 then " +
            "    redis.call('SETEX', KEYS[2], ARGV[3], ARGV[2]) " +
            "    return 1 " +
            "else " +
            "    return 0 " +
            "end";

    private static final String CANCEL_LUA =
            "local userStatus = redis.call('GET', KEYS[2]) " +
            "if userStatus == 'PAYMENT_PENDING' or not userStatus then " +
            "    redis.call('SADD', KEYS[1], ARGV[1]) " +
            "    redis.call('DEL', KEYS[2]) " +
            "    return 1 " +
            "else " +
            "    return 0 " +
            "end";

    @BeforeEach
    void setUp() {
        redisTemplate.delete(AVAILABLE_KEY);
        redisTemplate.delete(RESULT_KEY);
        redisTemplate.delete(WAITING_KEY);
    }

    @AfterEach
    void cleanUp() {
        redisTemplate.delete(AVAILABLE_KEY);
        redisTemplate.delete(RESULT_KEY);
        redisTemplate.delete(WAITING_KEY);
        // 순번 테스트용 키 정리
        Set<String> keys = redisTemplate.keys("test:active_user:*");
        if (keys != null && !keys.isEmpty()) redisTemplate.delete(keys);
    }

    // ── RESERVE Lua Script ───────────────────────────────────────────────

    @Test
    @DisplayName("RESERVE Lua: 가용 좌석이 있으면 1을 반환하고 좌석이 Set에서 제거된다")
    void reserveLua_success() {
        redisTemplate.opsForSet().add(AVAILABLE_KEY, "10");

        Long result = executeLua(RESERVE_LUA, List.of(AVAILABLE_KEY, RESULT_KEY), "10", "PAYMENT_PENDING", "600");

        assertThat(result).isEqualTo(1L);
        assertThat(redisTemplate.opsForSet().isMember(AVAILABLE_KEY, "10")).isFalse();
        assertThat(redisTemplate.opsForValue().get(RESULT_KEY)).isEqualTo("PAYMENT_PENDING");
    }

    @Test
    @DisplayName("RESERVE Lua: 이미 선점된 좌석이면 0을 반환하고 상태가 변경되지 않는다")
    void reserveLua_alreadyTaken() {
        // 좌석이 Set에 없는 상태 (이미 선점됨)

        Long result = executeLua(RESERVE_LUA, List.of(AVAILABLE_KEY, RESULT_KEY), "10", "PAYMENT_PENDING", "600");

        assertThat(result).isEqualTo(0L);
        assertThat(redisTemplate.hasKey(RESULT_KEY)).isFalse();
    }

    @Test
    @DisplayName("RESERVE Lua: 이미 예매 진행 중인 유저가 다른 좌석 요청하면 0을 반환하고 좌석은 유지된다")
    void reserveLua_duplicateUserBooking() {
        redisTemplate.opsForSet().add(AVAILABLE_KEY, "10", "20");
        redisTemplate.opsForValue().set(RESULT_KEY, "PAYMENT_PENDING");  // 이미 예매 진행 중

        Long result = executeLua(RESERVE_LUA, List.of(AVAILABLE_KEY, RESULT_KEY), "20", "PAYMENT_PENDING", "600");

        assertThat(result).isEqualTo(0L);
        assertThat(redisTemplate.opsForSet().isMember(AVAILABLE_KEY, "20")).isTrue();  // 좌석 유지
    }

    @Test
    @DisplayName("RESERVE Lua: 동시에 같은 좌석 선점 시도 시 하나만 성공한다 (원자성)")
    void reserveLua_atomicity() {
        redisTemplate.opsForSet().add(AVAILABLE_KEY, "10");

        Long first  = executeLua(RESERVE_LUA, List.of(AVAILABLE_KEY, RESULT_KEY), "10", "PAYMENT_PENDING", "600");
        Long second = executeLua(RESERVE_LUA, List.of(AVAILABLE_KEY, RESULT_KEY), "10", "PAYMENT_PENDING", "600");

        assertThat(first).isEqualTo(1L);
        assertThat(second).isEqualTo(0L);
    }

    // ── CANCEL Lua Script ────────────────────────────────────────────────

    @Test
    @DisplayName("CANCEL Lua: PAYMENT_PENDING 상태이면 취소되고 좌석이 복구된다")
    void cancelLua_success() {
        redisTemplate.opsForValue().set(RESULT_KEY, "PAYMENT_PENDING");

        Long result = executeLua(CANCEL_LUA, List.of(AVAILABLE_KEY, RESULT_KEY), "10");

        assertThat(result).isEqualTo(1L);
        assertThat(redisTemplate.opsForSet().isMember(AVAILABLE_KEY, "10")).isTrue();
        assertThat(redisTemplate.hasKey(RESULT_KEY)).isFalse();
    }

    @Test
    @DisplayName("CANCEL Lua: CONFIRMED 상태이면 취소 불가 — 0을 반환한다")
    void cancelLua_confirmedCannotCancel() {
        redisTemplate.opsForValue().set(RESULT_KEY, "CONFIRMED");

        Long result = executeLua(CANCEL_LUA, List.of(AVAILABLE_KEY, RESULT_KEY), "10");

        assertThat(result).isEqualTo(0L);
        assertThat(redisTemplate.opsForSet().isMember(AVAILABLE_KEY, "10")).isFalse();
        assertThat(redisTemplate.opsForValue().get(RESULT_KEY)).isEqualTo("CONFIRMED");
    }

    // ── ZSet 순서 보장 ───────────────────────────────────────────────────

    @Test
    @DisplayName("ZSet: 먼저 진입한 유저가 더 낮은 score를 가진다 (FIFO 보장)")
    void zset_firstInFirstOut() throws InterruptedException {
        for (long userId = 1; userId <= 5; userId++) {
            redisTemplate.opsForZSet().add(WAITING_KEY, String.valueOf(userId), System.currentTimeMillis());
            Thread.sleep(2);
        }

        Set<String> ordered = redisTemplate.opsForZSet().range(WAITING_KEY, 0, -1);

        assertThat(ordered).hasSize(5);
        assertThat(ordered.iterator().next()).isEqualTo("1");
    }

    // ── TTL 정합성 ───────────────────────────────────────────────────────

    @Test
    @DisplayName("active_user 토큰 TTL이 600초로 설정된다")
    void ttl_activeUserToken() {
        redisTemplate.opsForValue().set("test:active_user:1", "true", 600, TimeUnit.SECONDS);

        Long ttl = redisTemplate.getExpire("test:active_user:1", TimeUnit.SECONDS);

        assertThat(ttl).isGreaterThan(590L).isLessThanOrEqualTo(600L);
    }

    // ── helper ──────────────────────────────────────────────────────────

    private Long executeLua(String script, List<String> keys, String... args) {
        return redisTemplate.execute(new DefaultRedisScript<>(script, Long.class), keys, (Object[]) args);
    }
}
