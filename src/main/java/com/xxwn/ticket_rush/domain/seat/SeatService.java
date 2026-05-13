package com.xxwn.ticket_rush.domain.seat;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class SeatService {

    private final SeatRepository seatRepository;
    private final StringRedisTemplate redisTemplate;

    public void warmupSeats(Long eventId){

        String cacheKey = "event:" + eventId + ":available";
        redisTemplate.delete(cacheKey);

        List<Long> seats = seatRepository.findAvailableSeatIds(eventId);

        if(!seats.isEmpty()){
            String[] ids = seats.stream().map(String::valueOf).toArray(String[]::new);
            redisTemplate.opsForSet().add(cacheKey,ids);
            log.info("Redis 적재 완료 - 키:{}, 수량: {}", cacheKey, ids.length);
        } else {
            log.warn("warmup failed");
        }
    }
}
