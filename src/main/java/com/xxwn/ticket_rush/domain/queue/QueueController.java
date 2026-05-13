package com.xxwn.ticket_rush.domain.queue;

import com.xxwn.ticket_rush.global.sse.SseService;
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
    public ResponseEntity<?> join(@RequestHeader("X-USER-ID") String userId){
        long rank = waitingQueueService.registerQueue(userId);
        log.info("대기열등록 {} (rank: {})", userId, rank);
        return ResponseEntity.ok(java.util.Map.of("rank", rank));
    }
}
