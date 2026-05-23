package com.xxwn.ticket_rush.domain.booking;

import com.xxwn.ticket_rush.config.RabbitMQConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@Slf4j
public class BookingController {

    private final RabbitTemplate rabbitTemplate;
    private final StringRedisTemplate redisTemplate;

    @Value("${toss.amount:30000}")
    private int tossAmount;

    // booking:result TTL = 600초(10분) — active_user: 토큰 만료 시간과 일치
    private static final String BOOKING_RESULT_TTL = "600";

    // v1용: 클라이언트가 seatId 직접 지정 (중복 방지 포함)
    private static final String RESERVE_LUA =
            "if redis.call('EXISTS', KEYS[2]) == 1 then return 0 end " +
                    "if redis.call('SREM', KEYS[1], ARGV[1]) == 1 then " +
                    "    redis.call('SETEX', KEYS[2], ARGV[3], ARGV[2]) " +
                    "    return 1 " +
                    "else " +
                    "    return 0 " +
                    "end";

    // v2용: 백엔드가 SRANDMEMBER로 랜덤 좌석 추출 → SREM → SETEX 원자적 실행
    // 반환값: 선점된 seatId 문자열, 빈 Set이면 nil
    private static final String RANDOM_RESERVE_LUA =
            "local seatId = redis.call('SRANDMEMBER', KEYS[1]) " +
            "if not seatId then return nil end " +
            "redis.call('SREM', KEYS[1], seatId) " +
            "redis.call('SETEX', KEYS[2], ARGV[1], ARGV[2]) " +
            "return seatId";

    private static final String CANCEL_LUA =
            "local userStatus = redis.call('GET', KEYS[2]) " +
                "if userStatus == 'PAYMENT_PENDING' or not userStatus then " +
                    "redis.call('SADD', KEYS[1], ARGV[1]) " +
                    "redis.call('DEL', KEYS[2]) " +
                    "redis.call('DEL', KEYS[3]) " +
                    "return 1 " +
                "else " +
                    "return 0 " +
                    "end";

    @GetMapping("/seats/{eventId}")
    public ResponseEntity<Set<String>> getAvailableSeats(@PathVariable Long eventId){
        String cacheKey = "event:" + eventId + ":available";

        Set<String> availableSeats = redisTemplate.opsForSet().members(cacheKey);

        if(availableSeats == null || availableSeats.isEmpty()){
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.ok(availableSeats);
    }

    @PostMapping("/v1/bookings")
    public ResponseEntity<String> createBookingV1(@RequestHeader("X-USER-ID") String userId, @RequestBody BookingMessage request){

        // 중복 예매 방지 — 이미 예매 진행 중인 유저 거부 (V2와 동일한 유저 레벨 체크)
        String resultKey = "booking:result:" + userId;
        if (redisTemplate.hasKey(resultKey)) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body("이미 예매 진행 중입니다.");
        }

        String cacheKey = "event:" + request.eventId() +":available";

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
    public ResponseEntity<?> createBookingV2(@RequestHeader("X-USER-ID") String userId, @RequestBody BookingV2Request request){
        String cacheKey = "event:" + request.eventId() + ":available";
        String resultKey = "booking:result:" + userId;

        // 1. SRANDMEMBER + SREM + SETEX 원자적 실행 — 랜덤 좌석 선점
        DefaultRedisScript<String> script = new DefaultRedisScript<>(RANDOM_RESERVE_LUA, String.class);
        String pickedSeatId = redisTemplate.execute(
                script,
                Arrays.asList(cacheKey, resultKey),
                BOOKING_RESULT_TTL,
                "PAYMENT_PENDING"
        );

        // 2. 매진 판정 — active_user 토큰도 정리해야 다음 이벤트에서 대기열을 건너뛰지 않음
        if (pickedSeatId == null) {
            redisTemplate.delete("active_user:" + userId);
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", "매진되었습니다."));
        }

        Long seatId = Long.parseLong(pickedSeatId);

        // 3. orderId 생성 및 Redis 임시 저장 (결제 확인 시 사용)
        String orderId = UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        String orderKey = "payment:order:" + orderId;
        redisTemplate.opsForValue().set(orderKey,
                userId + ":" + seatId + ":" + request.eventId(),
                600, TimeUnit.SECONDS);

        log.info("[V2] 좌석 선점 성공 - user: {}, seat: {}, orderId: {}", userId, seatId, orderId);
        return ResponseEntity.ok(Map.of(
                "seatId", seatId,
                "orderId", orderId,
                "amount", tossAmount,
                "message", "좌석 선점 성공 — 결제를 진행해주세요."
        ));
    }

    @GetMapping("/status")
    public ResponseEntity<?> getBookingStatus(@RequestHeader("X-USER-ID") String userId) {
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
    public ResponseEntity<Void> cancelBooking(@RequestHeader("X-USER-ID") String userId, @RequestBody BookingMessage request){
        String cacheKey = "event:" + request.eventId() + ":available";
        String resultKey = "booking:result:" + userId;
        String activeUserKey = "active_user:" + userId;

        DefaultRedisScript<Long> script = new DefaultRedisScript<>(CANCEL_LUA, Long.class);
        Long result = redisTemplate.execute(
                script,
                Arrays.asList(cacheKey, resultKey, activeUserKey),
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
