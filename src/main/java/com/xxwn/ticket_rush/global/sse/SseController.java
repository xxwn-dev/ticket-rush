package com.xxwn.ticket_rush.global.sse;

import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequiredArgsConstructor
public class SseController {
    private final SseService sseService;

    @GetMapping(value = "/api/subscribe", produces = MediaType.TEXT_EVENT_STREAM_VALUE + ";charset=UTF-8")
    public SseEmitter subscribe(
            @RequestHeader(value = "X-USER-ID", required = false) String headerUserId,
            @RequestParam(value = "userId", required = false) String queryUserId) {
        String userId = headerUserId != null ? headerUserId : queryUserId;
        return sseService.subscribe(userId);
    }
}
