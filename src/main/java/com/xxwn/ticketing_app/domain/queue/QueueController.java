package com.xxwn.ticketing_app.domain.queue;

import com.xxwn.ticketing_app.global.sse.SseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/queue")
@Slf4j
public class QueueController {
    private final WaitingQueueService waitingQueueService;
    private final SseService sseEmitter;

    @PostMapping("/join")
    public ResponseEntity<?> join(@RequestHeader("X-USER-ID") Long userId){
        waitingQueueService.registerQueue(userId);
        log.info("대기열등록 {}", userId);
        return ResponseEntity.ok("대기열에 등록되었습니다.");
    }
}
