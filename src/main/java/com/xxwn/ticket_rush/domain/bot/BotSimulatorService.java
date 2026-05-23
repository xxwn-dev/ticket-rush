package com.xxwn.ticket_rush.domain.bot;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
public class BotSimulatorService {

    private static final int BOT_COUNT = 50;

    private final RestClient restClient;

    public BotSimulatorService(@Value("${server.port:8080}") int port) {
        this.restClient = RestClient.create("http://localhost:" + port);
    }

    public int trigger(Long eventId) {
        CountDownLatch latch = new CountDownLatch(BOT_COUNT);
        for (int i = 0; i < BOT_COUNT; i++) {
            Thread.startVirtualThread(() -> {
                String botId = UUID.randomUUID().toString();
                try {
                    // Phase 1: 대기열 진입 (동기 - 모든 봇이 완료될 때까지 대기)
                    restClient.post()
                            .uri("/api/queue/join")
                            .header("X-USER-ID", botId)
                            .retrieve()
                            .toBodilessEntity();
                } catch (Exception e) {
                    log.warn("[Bot] {} queue join 실패 - {}", botId.substring(0, 8), e.getMessage());
                } finally {
                    latch.countDown();
                }
                // Phase 2: 승격될 때까지 재시도 — 403(미승격)이면 1초 후 재시도, 200/409면 종료
                for (int attempt = 0; attempt < 60; attempt++) {
                    try {
                        Thread.sleep(1000);
                        restClient.post()
                                .uri("/api/v2/bookings")
                                .header("X-USER-ID", botId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .body(Map.of("eventId", eventId))
                                .retrieve()
                                .toEntity(String.class);
                        log.info("[Bot] {} 예매 성공", botId.substring(0, 8));
                        break; // 200 OK
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    } catch (Exception e) {
                        String msg = e.getMessage();
                        if (msg != null && msg.contains("409")) {
                            log.info("[Bot] {} 매진 — 종료", botId.substring(0, 8));
                            break; // 매진 → 더 이상 시도 불필요
                        }
                        // 403(미승격) 또는 기타 오류 → 재시도
                    }
                }
            });
        }
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        log.info("[Bot] {}개 봇 대기열 등록 완료 - eventId: {}", BOT_COUNT, eventId);
        return BOT_COUNT;
    }
}
