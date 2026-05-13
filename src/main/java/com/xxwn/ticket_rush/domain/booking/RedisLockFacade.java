package com.xxwn.ticket_rush.domain.booking;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
@RequiredArgsConstructor
@Slf4j
public class RedisLockFacade {

    private final RedissonClient redissonClient;
    private final BookingService bookingService;
    private final StringRedisTemplate redisTemplate;

    public void createBookingWithLock(String userId, Long seatId, Long eventId){
        RLock lock = redissonClient.getLock("lock:seat:" + seatId);
        String resultKey = "booking:result:" + userId;
        String cacheKey = "event:"+eventId+":available";

        try{
            boolean available = lock.tryLock(1, 3, TimeUnit.SECONDS);

            if(!available){
                throw new IllegalStateException("접속자가 많아 요청을 처리할 수 없습니다.");
            }

            //1. 레디스 좌석 화이트리스트 차감
            Long result = redisTemplate.opsForSet().remove(cacheKey, seatId.toString());

            if(result == null || result == 0){
                redisTemplate.opsForValue().set(resultKey, BookingStatus.REJECTED.name(), 600, TimeUnit.SECONDS);
                throw new IllegalStateException("이미 선점된 좌석입니다.");
            }

            try {
                //2. DB 트랜잭션
                bookingService.createBooking(userId, seatId, eventId);

                //3. 레디스 성공기록 (TTL 10분 — active_user: 토큰 만료와 일치)
                redisTemplate.opsForValue().set(resultKey, BookingStatus.PAYMENT_PENDING.name(), 600, TimeUnit.SECONDS);
            } catch (Exception e) {
                redisTemplate.opsForSet().add(cacheKey, seatId.toString());
                redisTemplate.opsForValue().set(resultKey, BookingStatus.REJECTED.name(), 1, TimeUnit.MINUTES);
                throw e;
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        } finally {
            if(lock.isHeldByCurrentThread()){
                lock.unlock();
            }
        }
    }
}
