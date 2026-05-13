package com.xxwn.ticket_rush.unit;

import com.xxwn.ticket_rush.domain.queue.WaitingQueueService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WaitingQueueServiceTest {

    @Mock StringRedisTemplate redisTemplate;
    @Mock ZSetOperations<String, String> zSetOps;

    @InjectMocks WaitingQueueService waitingQueueService;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForZSet()).thenReturn(zSetOps);
    }

    @Test
    @DisplayName("registerQueue: 올바른 키에 userId 문자열로 ZSet에 등록된다")
    void registerQueue_addsUserToZSet() {
        waitingQueueService.registerQueue("00000000-0000-0000-0000-000000000042");

        verify(zSetOps).add(eq("event:waiting_queue"), eq("00000000-0000-0000-0000-000000000042"), anyDouble());
    }

    @Test
    @DisplayName("registerQueue: score는 현재 시각(ms) 기반이므로 양수여야 한다")
    void registerQueue_scoreIsPositive() {
        ArgumentCaptor<Double> scoreCaptor = ArgumentCaptor.forClass(Double.class);

        waitingQueueService.registerQueue("00000000-0000-0000-0000-000000000001");

        verify(zSetOps).add(eq("event:waiting_queue"), eq("00000000-0000-0000-0000-000000000001"), scoreCaptor.capture());
        assertThat(scoreCaptor.getValue()).isPositive();
    }

    @Test
    @DisplayName("registerQueue: 서로 다른 userId는 서로 다른 score로 등록된다")
    void registerQueue_differentUsersHaveIncreasingScores() throws InterruptedException {
        ArgumentCaptor<Double> scoreCaptor = ArgumentCaptor.forClass(Double.class);

        waitingQueueService.registerQueue("00000000-0000-0000-0000-000000000001");
        Thread.sleep(2);
        waitingQueueService.registerQueue("00000000-0000-0000-0000-000000000002");

        verify(zSetOps, times(2)).add(eq("event:waiting_queue"), anyString(), scoreCaptor.capture());
        assertThat(scoreCaptor.getAllValues().get(0))
                .isLessThan(scoreCaptor.getAllValues().get(1));
    }
}
