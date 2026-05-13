package com.xxwn.ticket_rush.global.sse;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
public class SseService {

    private static final Map<String, SseEmitter> waitingEmitters = new ConcurrentHashMap<>();

    public SseEmitter subscribe(String userId){
        log.info("SSE 구독 시도 - userId: {}", userId);

        SseEmitter emitter = new SseEmitter(0L);
        waitingEmitters.put(userId, emitter);

        emitter.onCompletion(() -> waitingEmitters.remove(userId));
        emitter.onTimeout(() -> waitingEmitters.remove(userId));

        try {
            emitter.send(SseEmitter.event().name("connect").data("connected!"));
        } catch (Exception e){
            //IOException?
            log.error("SSE connection error", e);
        }
        return emitter;
    }

    public Set<String> getWaitingUserIds(){
        return waitingEmitters.keySet();
    }

    @Async("sseTaskExecutor")
    public void sendMoveSignal(String userId, Object result){
        SseEmitter emitter = waitingEmitters.remove(userId);
        if(emitter != null){
            try {
                log.info("SSE Send Thread: {}", Thread.currentThread().getName());
                emitter.send(SseEmitter.event().name("queue").data(result));
                emitter.complete();
                log.info("대기열 통과: 유저 {} 연결 종료 및 입장 처리", userId);
            } catch (IOException e){
                emitter.completeWithError(e);
                log.warn("SSE 전송 중 연결 끊김: {}", userId);
            }
        }
    }

    public boolean exists(String userId){
        return waitingEmitters.containsKey(userId);
    }

    // Cloudflare Tunnel 100초 타임아웃 대응 — 30초마다 comment 이벤트로 연결 유지
    @Scheduled(fixedDelay = 30_000)
    public void heartbeat() {
        waitingEmitters.forEach((userId, emitter) -> {
            try {
                emitter.send(SseEmitter.event().comment("ping"));
            } catch (IOException e) {
                waitingEmitters.remove(userId);
                emitter.completeWithError(e);
            }
        });
    }
    public void sendStatusUpdate(String userId, Object result){
        SseEmitter emitter = waitingEmitters.get(userId);
        if(emitter != null){
            try {
                emitter.send(SseEmitter.event().name("queue").data(result));
            } catch (IOException e) {
                log.warn("SSE 전송 실패로 제거: {}" , userId);
                waitingEmitters.remove(userId);
                emitter.completeWithError(e);
            }
        }
    }

}
