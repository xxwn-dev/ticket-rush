package com.xxwn.ticket_rush.domain.payment;

import com.xxwn.ticket_rush.config.RabbitMQConfig;
import com.xxwn.ticket_rush.domain.booking.BookingMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/payments")
@RequiredArgsConstructor
@Slf4j
public class PaymentController {

    private final TossPaymentService tossPaymentService;
    private final StringRedisTemplate redisTemplate;
    private final RabbitTemplate rabbitTemplate;

    @PostMapping("/cancel")
    public ResponseEntity<?> cancel(
            @RequestHeader("X-USER-ID") String userId,
            @RequestBody Map<String, String> body) {

        String orderId = body.get("orderId");
        if (orderId == null) {
            return ResponseEntity.badRequest().body(Map.of("message", "orderId is required"));
        }

        String orderKey = "payment:order:" + orderId;
        String orderValue = redisTemplate.opsForValue().get(orderKey);
        if (orderValue == null) {
            return ResponseEntity.ok(Map.of("message", "이미 처리된 주문입니다."));
        }

        String[] parts = orderValue.split(":");
        String storedUserId = parts[0];
        Long seatId = Long.parseLong(parts[1]);
        Long eventId = Long.parseLong(parts[2]);

        if (!storedUserId.equals(userId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", "권한이 없습니다."));
        }

        // 좌석 복원 + 세션 정리
        redisTemplate.opsForSet().add("event:" + eventId + ":available", seatId.toString());
        redisTemplate.delete("active_user:" + userId);
        redisTemplate.delete("booking:result:" + userId);
        redisTemplate.delete(orderKey);

        log.info("[결제 취소] user: {}, seat: {}, orderId: {}", userId, seatId, orderId);
        return ResponseEntity.ok(Map.of("message", "취소 완료. 다시 대기열에서 진행해주세요."));
    }

    @PostMapping("/confirm")
    public ResponseEntity<?> confirm(
            @RequestHeader("X-USER-ID") String userId,
            @RequestBody PaymentConfirmRequest request) {

        // orderId로 좌석/이벤트 정보 조회
        String orderKey = "payment:order:" + request.orderId();
        String orderValue = redisTemplate.opsForValue().get(orderKey);
        if (orderValue == null) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("message", "유효하지 않은 주문입니다."));
        }

        // orderValue 형식: userId:seatId:eventId
        String[] parts = orderValue.split(":");
        String storedUserId = parts[0];
        Long seatId = Long.parseLong(parts[1]);
        Long eventId = Long.parseLong(parts[2]);

        if (!storedUserId.equals(userId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("message", "권한이 없습니다."));
        }

        try {
            tossPaymentService.confirm(request.paymentKey(), request.orderId(), request.amount());
        } catch (TossPaymentException e) {
            log.error("[결제 승인 실패] orderId: {}, reason: {}", request.orderId(), e.getMessage());
            // ALREADY_PROCESSING_REQUEST: 첫 번째 요청이 이미 처리 중 → 좌석 복원 불필요
            if (e.getMessage().contains("ALREADY_PROCESSING_REQUEST")) {
                return ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(Map.of("message", "이미 처리 중인 요청입니다."));
            }
            // 그 외 결제 실패 시 좌석 복원
            String cacheKey = "event:" + eventId + ":available";
            String resultKey = "booking:result:" + userId;
            redisTemplate.opsForSet().add(cacheKey, seatId.toString());
            redisTemplate.delete(resultKey);
            redisTemplate.delete(orderKey);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("message", "결제 승인에 실패했습니다."));
        }

        // 결제 성공 → RabbitMQ로 DB 확정
        redisTemplate.delete(orderKey);
        BookingMessage message = new BookingMessage(userId, seatId, eventId);
        rabbitTemplate.convertAndSend(RabbitMQConfig.BOOKING_EXCHANGE, RabbitMQConfig.BOOKING_ROUTING_KEY_V2, message);

        log.info("[결제 완료] user: {}, seat: {}, orderId: {}", userId, seatId, request.orderId());
        return ResponseEntity.ok(Map.of("seatId", seatId, "message", "예매가 완료되었습니다."));
    }
}
