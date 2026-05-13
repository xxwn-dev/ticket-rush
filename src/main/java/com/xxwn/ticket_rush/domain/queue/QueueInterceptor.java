package com.xxwn.ticket_rush.domain.queue;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
@RequiredArgsConstructor
@Slf4j
public class QueueInterceptor implements HandlerInterceptor {

    private final StringRedisTemplate redisTemplate;
    private static final String ACTIVE_USER_PREFIX = "active_user:";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // CORS preflight 통과
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        // 1. 헤더에서 유저 ID 추출
        String userId = request.getHeader("X-USER-ID");

        if (userId == null || userId.isEmpty()) {
            log.warn("접속 거부: 유저 ID가 헤더에 없습니다.");
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            response.getWriter().write("User ID is missing in header.");
            return false; // 컨트롤러 진입 차단
        }

        // 2. Redis에 해당 유저의 활성화 토큰이 있는지 확인
        String activeKey = ACTIVE_USER_PREFIX +userId;
        boolean isActive = redisTemplate.hasKey(activeKey);

        if (!isActive) {
            log.info("접속 거부: 유저 {}는 아직 대기 상태이거나 토큰이 만료되었습니다.", userId);
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("아직 입장 순서가 아니거나 입장권이 만료되었습니다.");
            return false; // 컨트롤러 진입 차단
        }

        // 3. 토큰이 있으면 통과!
        log.info("접속 허가 {}: 유저 {} 입장 성공", request.getRequestURI(),userId);
        return true;
    }
}
