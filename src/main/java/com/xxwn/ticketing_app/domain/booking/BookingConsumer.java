package com.xxwn.ticketing_app.domain.booking;

import com.xxwn.ticketing_app.config.RabbitMQConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
@RequiredArgsConstructor
@Slf4j
public class BookingConsumer {

    private final BookingService bookingService;
    private final RedisLockFacade redisLockFacade;
    private final StringRedisTemplate redisTemplate;

    @RabbitListener(queues = RabbitMQConfig.BOOKING_QUEUE_V1)
    public void receiveV1Message(BookingMessage message) {
        log.info("Received from Queue: {}", message);

        try {
            redisLockFacade.createBookingWithLock(message.userId(), message.seatId(), message.concertId());
            log.info("Success - user: {}, seat:{}", message.userId(), message.seatId());
        } catch (IllegalStateException e){
            log.warn("Booking rejected: {}", e.getMessage());
        } catch (Exception e) {
            log.error("System error during booking", e);
        }
    }

    @RabbitListener(queues = RabbitMQConfig.BOOKING_QUEUE_V2)
    public void receiveV2Message(BookingMessage message) {
        log.info("[V2 Consumer]: {}", message.seatId());
        String cacheKey = "concert:" + message.concertId() + ":available";
        String resultKey = "booking:result:" + message.userId();
        try {
            bookingService.processV2Booking(message.userId(), message.seatId(), message.concertId());
        } catch (IllegalStateException e) {
            log.error("이미 예약된 좌석입니다. 재고 복구 없이 종료합니다.");
            redisTemplate.opsForValue().set(resultKey, "REJECTED", 600, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.error("[V2 CONSUMER] DB 저장 실패! Redis 복구 진행", e);
            redisTemplate.opsForSet().add(cacheKey, message.seatId().toString());
        }
    }

    @RabbitListener(queues = RabbitMQConfig.BOOKING_CANCEL_QUEUE)
    public void receiveCancelMessage(BookingMessage message){
        log.info("[CANCEL consumer]: {}", message.seatId());

        try {
            bookingService.processCancel(message.userId(), message.seatId(), message.concertId());
        } catch (Exception e) {
            // 1. 재시도 안 함 (시스템 전체 지연 방지)
            // 2. Redis 복구 안 함 (좌석이라도 다시 팔리게 둠)
            // 3. 로그만 남기고 이 건은 포기함
            log.error("[CRITICAL] 취소 DB 반영 실패 - 유저: {}, 좌석: {}. 사유: {}",
                    message.userId(), message.seatId(), e.getMessage());
        }
    }
}
