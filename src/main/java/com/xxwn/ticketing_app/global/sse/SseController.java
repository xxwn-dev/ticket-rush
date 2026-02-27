package com.xxwn.ticketing_app.global.sse;

import com.xxwn.ticketing_app.domain.queue.QueueResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;

@RestController
@RequiredArgsConstructor
public class SseController {
    private final SseService sseService;
    private final StringRedisTemplate redisTemplate;
    private static final String ACTIVE_USER_PREFIX = "active_user:";

    @GetMapping(value = "/api/subscribe", produces = MediaType.TEXT_EVENT_STREAM_VALUE + ";charset=UTF-8")
    public SseEmitter subscribe(@RequestHeader("X-USER-ID") Long userId){
        if(redisTemplate.hasKey(ACTIVE_USER_PREFIX + userId)){
            SseEmitter emitter = new SseEmitter(1000L);
            try {
                emitter.send(SseEmitter.event().name("queue").data(new QueueResponse(0L, 0L, "GO_BOOKING")));
                emitter.complete();
            } catch (IOException e){
                emitter.completeWithError(e);
            }
            return emitter;
        }
        return sseService.subscribe(userId);
    }
}
