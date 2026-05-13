package com.xxwn.ticket_rush.domain.queue;

import com.xxwn.ticket_rush.config.RedisConfig;
import com.xxwn.ticket_rush.global.sse.SseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

@Component
@RequiredArgsConstructor
@Slf4j
public class QueueScheduler {

    private final StringRedisTemplate redisTemplate;
    private final SseService sseService;

    private static final String WAITING_KEY = "event:waiting_queue";
    private static final String ACTIVE_USER_PREFIX = "active_user:";
    private static final int ALLOW_COUNT = 3;

    // 실서비스 기준 10분 (좌석 선택 + 결제 완료 시간)
    static final int ACTIVE_USER_TTL_SECONDS = 600;

    // 매 틱마다 재생성하지 않도록 바이트 배열 사전 계산
    private static final byte[] WAITING_KEY_BYTES = WAITING_KEY.getBytes(StandardCharsets.UTF_8);
    private static final byte[] CHANNEL_BYTES =
            RedisConfig.QUEUE_PROMOTED_CHANNEL.getBytes(StandardCharsets.UTF_8);
    private static final byte[] TRUE_BYTES = "true".getBytes(StandardCharsets.UTF_8);

    /**
     * [프로모션 스케줄러] 500ms 간격
     *
     * 대기열에서 최대 ALLOW_COUNT 명을 꺼내 Redis Pipeline 으로 한 번에:
     *   1) active_user:{id} 토큰 발급 (TTL 10분)
     *   2) ZSet 에서 제거
     *   3) Pub/Sub PUBLISH — 모든 서버 인스턴스의 QueuePromotionListener 에 전달
     *
     * 프로모션은 빠른 반응이 중요하므로 500ms 를 유지합니다.
     * 순번 업데이트(updateWaitingStatus)와 분리해 각 스케줄러가 가볍게 실행됩니다.
     */
    @Scheduled(fixedDelay = 500)
    public void promoteUsers() {
        Set<String> waitingUsers = redisTemplate.opsForZSet().range(WAITING_KEY, 0, ALLOW_COUNT - 1);
        if (waitingUsers == null || waitingUsers.isEmpty()) return;

        redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
            for (String userIdStr : waitingUsers) {
                byte[] activeKeyBytes = (ACTIVE_USER_PREFIX + userIdStr).getBytes(StandardCharsets.UTF_8);
                byte[] userIdBytes = userIdStr.getBytes(StandardCharsets.UTF_8);

                connection.stringCommands().setEx(activeKeyBytes, ACTIVE_USER_TTL_SECONDS, TRUE_BYTES);
                connection.zSetCommands().zRem(WAITING_KEY_BYTES, userIdBytes);
                connection.publish(CHANNEL_BYTES, userIdBytes);
            }
            return null;
        });

        log.info("[프로모션] {}명 입장 처리 완료 (Pipeline + Pub/Sub)", waitingUsers.size());
    }

    /**
     * [순번 업데이트 스케줄러] 2000ms 간격
     *
     * 아직 대기 중인 유저에게 현재 순번 SSE 알림을 보냅니다.
     * 2초 갱신은 UX 상 충분하며, 500ms 프로모션 스케줄러와 분리해
     * 서로 실행 시간이 영향을 주지 않도록 합니다.
     *
     * 이 서버에 SSE 연결된 유저에게만 전송합니다 (분산 환경에서 각 인스턴스가 자신의 연결만 담당).
     */
    @Scheduled(fixedDelay = 2000)
    public void updateWaitingStatus() {
        List<String> allWaitingList = redisTemplate.opsForZSet().range(WAITING_KEY, 0, -1).stream().toList();
        if (allWaitingList.isEmpty()) return;

        long totalWait = allWaitingList.size();
        for (int i = 0; i < totalWait; i++) {
            final int index = i;
            final String userIdStr = allWaitingList.get(i);
            Thread.startVirtualThread(() -> {
                try {
                    if (sseService.exists(userIdStr)) {
                        long rank = index + 1;
                        sseService.sendStatusUpdate(userIdStr, new QueueResponse(rank, totalWait - rank, "WAITING"));
                    }
                } catch (Exception e) {
                    log.error("SSE 순번 전송 오류: {}", userIdStr, e);
                }
            });
        }
    }
}
