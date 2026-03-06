package com.xxwn.ticketing_app.unit;

import com.xxwn.ticketing_app.domain.queue.QueueInterceptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class QueueInterceptorTest {

    @Mock StringRedisTemplate redisTemplate;

    @InjectMocks QueueInterceptor queueInterceptor;

    private MockHttpServletRequest request;
    private MockHttpServletResponse response;

    @BeforeEach
    void setUp() {
        request = new MockHttpServletRequest();
        response = new MockHttpServletResponse();
    }

    @Test
    @DisplayName("X-USER-ID 헤더가 없으면 400을 반환하고 false를 반환한다")
    void preHandle_missingUserId_returns400() throws Exception {
        boolean result = queueInterceptor.preHandle(request, response, new Object());

        assertThat(result).isFalse();
        assertThat(response.getStatus()).isEqualTo(400);
    }

    @Test
    @DisplayName("X-USER-ID 헤더가 빈 문자열이면 400을 반환한다")
    void preHandle_emptyUserId_returns400() throws Exception {
        request.addHeader("X-USER-ID", "");

        boolean result = queueInterceptor.preHandle(request, response, new Object());

        assertThat(result).isFalse();
        assertThat(response.getStatus()).isEqualTo(400);
    }

    @Test
    @DisplayName("active_user 토큰이 없으면 403을 반환하고 false를 반환한다")
    void preHandle_noActiveToken_returns403() throws Exception {
        request.addHeader("X-USER-ID", "1");
        when(redisTemplate.hasKey("active_user:1")).thenReturn(false);

        boolean result = queueInterceptor.preHandle(request, response, new Object());

        assertThat(result).isFalse();
        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    @DisplayName("active_user 토큰이 있으면 true를 반환하고 요청을 통과시킨다")
    void preHandle_withActiveToken_returnsTrue() throws Exception {
        request.addHeader("X-USER-ID", "1");
        when(redisTemplate.hasKey("active_user:1")).thenReturn(true);

        boolean result = queueInterceptor.preHandle(request, response, new Object());

        assertThat(result).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
    }
}
