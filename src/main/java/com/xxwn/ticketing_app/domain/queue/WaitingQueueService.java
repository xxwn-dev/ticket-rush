package com.xxwn.ticketing_app.domain.queue;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class WaitingQueueService {

    private final StringRedisTemplate redisTemplate;
    private static final String WAITING_KEY = "concert:waiting_queue";

    public void registerQueue(Long userId){
        double score = System.currentTimeMillis();
        redisTemplate.opsForZSet().add(WAITING_KEY, userId.toString(), score);
    }
}
