package com.xxwn.ticketing_app.domain.booking;

import com.xxwn.ticketing_app.config.RabbitMQConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@Slf4j
public class BookingController {

    private final RabbitTemplate rabbitTemplate;
    private final StringRedisTemplate redisTemplate;

    // booking:result TTL = 600초(10분) — active_user: 토큰 만료 시간과 일치
    private static final String BOOKING_RESULT_TTL = "600";

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
                    "redis.call('SADD', KEYS[1], ARGV[1]) " +
                    "redis.call('DEL', KEYS[2]) " +
                    "return 1 " +
                "else " +
                    "return 0 " +
                    "end";

    @GetMapping("/seats/{concertId}")
    public ResponseEntity<Set<String>> getAvailableSeats(@PathVariable Long concertId){
        String cacheKey = "concert:" + concertId + ":available";

        Set<String> availableSeats = redisTemplate.opsForSet().members(cacheKey);

        if(availableSeats == null || availableSeats.isEmpty()){
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.ok(availableSeats);
    }

    @PostMapping("/v1/bookings")
    public ResponseEntity<String> createBookingV1(@RequestHeader("X-USER-ID") Long userId, @RequestBody BookingMessage request){

        String cacheKey = "concert:" + request.concertId() +":available";

        Boolean isAvailable = redisTemplate.opsForSet().isMember(cacheKey, request.seatId().toString());

        if(!isAvailable){
            return ResponseEntity.status(HttpStatus.CONFLICT).body("이미 선택된 좌석입니다.");
        }

        BookingMessage message = BookingMessage.create(userId, request);
        rabbitTemplate.convertAndSend(
                RabbitMQConfig.BOOKING_EXCHANGE,
                RabbitMQConfig.BOOKING_ROUTING_KEY_V1,
                message
        );
        return ResponseEntity.accepted().body("예약 요청이 접수되었습니다.");
    }

    @PostMapping("/v2/bookings")
    public ResponseEntity<String> createBookingV2(@RequestHeader("X-USER-ID") Long userId, @RequestBody BookingMessage request){
        String cacheKey = "concert:" + request.concertId() + ":available";
        String resultKey = "booking:result:" + userId;
        // 1. 루아 스크립트 실행 (원자적 차감 + 상태 기록)
        DefaultRedisScript<Long> script = new DefaultRedisScript<>(RESERVE_LUA, Long.class);
        Long result = redisTemplate.execute(
                script,
                Arrays.asList(cacheKey, resultKey),
                request.seatId().toString(),
                "PAYMENT_PENDING",
                BOOKING_RESULT_TTL
                );

        // 2. 결과 판정
        if (result == null || result == 0) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body("이미 선점되었거나 존재하지 않는 좌석입니다.");
        }

        // 3. 큐 발송 (복구 로직 포함)
        try {
            BookingMessage message = BookingMessage.create(userId, request);
            rabbitTemplate.convertAndSend(
                    RabbitMQConfig.BOOKING_EXCHANGE,
                    RabbitMQConfig.BOOKING_ROUTING_KEY_V2,
                    message
            );
            rabbitTemplate.convertAndSend(
                    RabbitMQConfig.HOLD_EXCHANGE,
                    RabbitMQConfig.HOLD_ROUTING_KEY,
                    message
            );
            log.info("[V2 Publish Success] User: {}, Seat: {}", userId, request.seatId());
        } catch (Exception e) {
            log.error("[V2 Publish Failed]");
            redisTemplate.opsForSet().add(cacheKey, request.seatId().toString());
            redisTemplate.delete(resultKey);
            return ResponseEntity.internalServerError().body("시스템 오류로 예약 요청을 처리하지 못했습니다.");
        }
        return ResponseEntity.accepted().body("좌석 선점 성공, 결제 진행해주세요");
    }

    @GetMapping("/status")
    public ResponseEntity<?> getBookingStatus(@RequestHeader("X-USER-ID") Long userId) {
        // 컨슈머가 예매 처리 후 Redis에 저장해둔 결과를 읽음
        String resultKey = "booking:result:" + userId;
        String statusStr = redisTemplate.opsForValue().get(resultKey);

        if (statusStr == null) {
            return ResponseEntity.ok(Map.of("status", "WAITING", "message", "대기열 통과 후 예매 처리 중"));
        }
        //redisTemplate.delete(resultKey);
        try {
            // 3. String 상태값을 Enum으로 변환
            BookingStatus status = BookingStatus.valueOf(statusStr);

            // 4. 상태별 응답 처리
            return switch (status) {
                case PAYMENT_PENDING -> ResponseEntity.ok(Map.of(
                        "status", "SUCCESS",
                        "message", "좌석 선점 성공! 결제를 진행해주세요."
                ));
                case CONFIRMED -> ResponseEntity.ok(Map.of(
                        "status", "CONFIRMED",
                        "message", "이미 예매가 완료된 건입니다."
                ));
                case CANCELLED, REJECTED -> ResponseEntity.ok(Map.of(
                        "status", "FAIL",
                        "message", "예매 실패: 이미 선점된 좌석이거나 요청이 거절되었습니다."
                ));
                default -> ResponseEntity.ok(Map.of(
                        "status", "UNKNOWN",
                        "message", "상태를 확인할 수 없습니다."
                ));
            };
        } catch (IllegalArgumentException e) {
            // Enum 변환 실패 시 예외 처리
            return ResponseEntity.internalServerError().body("잘못된 상태 값입니다.");
        }
    }

    @DeleteMapping("/v2/bookings")
    public ResponseEntity<Void> cancelBooking(@RequestHeader("X-USER-ID") Long userId, @RequestBody BookingMessage request){
        String cacheKey = "concert:" + request.concertId() + ":available";
        String resultKey = "booking:result:" + userId;

        DefaultRedisScript<Long> script = new DefaultRedisScript<>(CANCEL_LUA, Long.class);
        Long result = redisTemplate.execute(
                script,
                Arrays.asList(cacheKey, resultKey),
                request.seatId().toString()
        );
        if(result != null && result > 0){
            BookingMessage message = BookingMessage.create(userId, request);
            rabbitTemplate.convertAndSend(
                    RabbitMQConfig.BOOKING_CANCEL_EXCHANGE,
                    RabbitMQConfig.BOOKING_CANCEL_ROUTING_KEY,
                    message
            );
            return ResponseEntity.accepted().build();
        }
        return ResponseEntity.status(HttpStatus.CONFLICT).build();
    }
}
