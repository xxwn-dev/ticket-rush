package com.xxwn.ticketing_app.global.sse;

import com.xxwn.ticketing_app.domain.queue.QueueResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;

/**
 * Redis Pub/Sub 구독자 — 대기열 입장 허가 이벤트를 수신해 해당 서버에 연결된 SSE 클라이언트에 신호를 전달한다.
 *
 * 분산 서버 환경에서 QueueScheduler 가 PUBLISH 하면, 모든 서버 인스턴스의 이 리스너가 메시지를 수신한다.
 * 각 인스턴스는 자신의 waitingEmitters 에서 해당 userId 를 찾아 SSE 를 전송한다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class QueuePromotionListener implements MessageListener {

    private final SseService sseService;

    @Override
    public void onMessage(Message message, byte[] pattern) {
        String userIdStr = new String(message.getBody()).trim();
        try {
            Long userId = Long.parseLong(userIdStr);
            log.info("[Pub/Sub] 입장 신호 수신 - userId: {}", userId);
            // @Async → sseTaskExecutor(가상 스레드)로 위임, 리스너 스레드는 즉시 반환
            sseService.sendMoveSignal(userId, new QueueResponse(0L, 0L, "GO_BOOKING"));
        } catch (NumberFormatException e) {
            log.error("[Pub/Sub] 잘못된 userId 메시지: '{}'", userIdStr);
        }
    }
}
