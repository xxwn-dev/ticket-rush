package com.xxwn.ticket_rush.domain.bot;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.Map;
import java.util.UUID;

@Service
@Slf4j
public class BotSimulatorService {

    private static final int BOT_COUNT = 150;

    private final RestClient restClient;

    public BotSimulatorService(@Value("${server.port:8080}") int port) {
        this.restClient = RestClient.create("http://localhost:" + port);
    }

    public int trigger(Long eventId) {
        for (int i = 0; i < BOT_COUNT; i++) {
            Thread.startVirtualThread(() -> runBot(eventId));
        }
        log.info("[Bot] {}개 봇 실행 시작 - eventId: {}", BOT_COUNT, eventId);
        return BOT_COUNT;
    }

    private void runBot(Long eventId) {
        String botId = UUID.randomUUID().toString();
        try {
            // 1. 대기열 진입
            restClient.post()
                    .uri("/api/queue/join")
                    .header("X-USER-ID", botId)
                    .retrieve()
                    .toBodilessEntity();

            // 2. QueueScheduler 승격 대기
            Thread.sleep(1000);

            // 3. 랜덤 좌석 예매 시도
            var result = restClient.post()
                    .uri("/api/v2/bookings")
                    .header("X-USER-ID", botId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("eventId", eventId))
                    .retrieve()
                    .toEntity(String.class);

            int status = result != null ? result.getStatusCode().value() : -1;
            log.info("[Bot] {} 완료 - status: {}", botId.substring(0, 8), status);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.warn("[Bot] {} 실패 - {}", botId.substring(0, 8), e.getMessage());
        }
    }
}
