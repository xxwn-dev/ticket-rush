package com.xxwn.ticket_rush.domain.bot;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/bot")
@RequiredArgsConstructor
public class BotController {

    private final BotSimulatorService botSimulatorService;

    // 데모 전용 public 엔드포인트 — 인증 없음
    @PostMapping("/trigger")
    public ResponseEntity<Map<String, Object>> trigger(@RequestBody Map<String, Long> body) {
        Long eventId = body.get("eventId");
        if (eventId == null) {
            return ResponseEntity.badRequest().body(Map.of("message", "eventId is required"));
        }
        int triggered = botSimulatorService.trigger(eventId);
        return ResponseEntity.ok(Map.of("triggered", triggered));
    }
}
