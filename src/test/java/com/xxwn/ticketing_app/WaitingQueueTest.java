package com.xxwn.ticketing_app;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.MediaType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
public class WaitingQueueTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @BeforeEach
    void setUp() {
        redisTemplate.delete("concert:waiting_queue");
        Set<String> keys = redisTemplate.keys("active_user:*");
        if(!keys.isEmpty()) redisTemplate.delete(keys);

        redisTemplate.delete("concert:1:available");
        redisTemplate.opsForSet().add("concert:1:available", "10", "11", "12");
    }


    @Test
    @DisplayName("전체 시나리오: SSE 구독 -> 대기열 진입 -> 활성화 후 좌석 조회 -> 예매 요청")
    void fullBookingFlowTest() throws Exception {
        String userId = "100";
        String concertId = "1";

        // 1. SSE 구독 (인터셉터 제외 경로여야 함)
        MvcResult sseResult = mockMvc.perform(get("/api/subscribe/" + userId))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted())
                .andReturn();

        // 2. 대기열 진입 (X-USER-ID 헤더 사용)
        mockMvc.perform(post("/api/queue/join")
                        .header("X-USER-ID", userId))
                .andExpect(status().isOk());

        // 3. 강제 활성화 (스케줄러가 통과시킨 상황 시뮬레이션)
        redisTemplate.opsForValue().set("active_user:" + userId, "true", Duration.ofMinutes(5));

        // 4. 좌석 조회 (인터셉터 통과 확인)
        mockMvc.perform(get("/api/bookings/seats/" + concertId)
                        .header("X-USER-ID", userId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(3));

        // 5. 예매 요청 (RabbitMQ 비동기 접수 확인)
        String bookingJson = "{\"concertId\":1, \"seatId\": 10}";
        mockMvc.perform(post("/api/bookings")
                        .header("X-USER-ID", userId)
                        .contentType(String.valueOf(MediaType.APPLICATION_JSON))
                        .content(bookingJson))
                .andExpect(status().isAccepted()) // 202 Accepted
                .andExpect(content().string("예약 요청이 접수되었습니다."));
    }

    @Test
    @DisplayName("동시성 테스트: 1000명 진입 시 Redis ZSET 정합성 확인")
    void concurrencyWithQueueTest() throws Exception {
        int threadCount = 1000;
        CountDownLatch latch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        ExecutorService executorService = Executors.newFixedThreadPool(32);

        for (int i = 1; i <= threadCount; i++) {
            final String userId = String.valueOf(i);
            executorService.submit(() -> {
                try {
                    latch.await();
                    // 헤더 기반으로 변경된 join API 호출
                    mockMvc.perform(post("/api/queue/join")
                            .header("X-USER-ID", userId));
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    doneLatch.countDown();
                }
            });
        }
        latch.countDown();
        doneLatch.await();

        Long totalCount = redisTemplate.opsForZSet().zCard("concert:waiting_queue");
        assertThat(totalCount).isEqualTo(1000L);
    }

    @Test
    @DisplayName("인터셉터 보안 테스트: 활성화되지 않은 유저는 좌석 조회가 거부되어야 한다")
    void securityTest() throws Exception {
        String waitingUserId = "999";

        // active_user 키가 없는 상태에서 조회 시도
        mockMvc.perform(get("/api/bookings/seats/1")
                        .header("X-USER-ID", waitingUserId))
                .andExpect(status().isForbidden()); // 403 Forbidden
    }
}
