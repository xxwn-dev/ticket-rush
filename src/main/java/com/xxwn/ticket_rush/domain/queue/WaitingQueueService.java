package com.xxwn.ticket_rush.domain.queue;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class WaitingQueueService {

    private final StringRedisTemplate redisTemplate;
    private static final String WAITING_KEY = "event:waiting_queue";

    public long registerQueue(String userId){
        double score = System.currentTimeMillis();
        redisTemplate.opsForZSet().add(WAITING_KEY, userId, score);
        Long rank = redisTemplate.opsForZSet().rank(WAITING_KEY, userId);
        return rank != null ? rank + 1 : 1L;
    }
}
