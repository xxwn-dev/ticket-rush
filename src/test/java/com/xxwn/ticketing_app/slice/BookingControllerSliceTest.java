package com.xxwn.ticketing_app.slice;

import com.xxwn.ticketing_app.domain.booking.BookingController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Set;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(BookingController.class)
class BookingControllerSliceTest {

    @Autowired MockMvc mockMvc;

    @MockitoBean RabbitTemplate rabbitTemplate;
    @MockitoBean StringRedisTemplate redisTemplate;

    @SuppressWarnings("unchecked")
    private final SetOperations<String, String> setOps = mock(SetOperations.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> valueOps = mock(ValueOperations.class);

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForSet()).thenReturn(setOps);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        // 인터셉터 기본 통과: active_user 토큰 존재
        when(redisTemplate.hasKey(anyString())).thenReturn(true);
    }

    // ── V2 예매 ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("V2 예매: 좌석이 가용하면 202 Accepted 반환")
    void createBookingV2_success() throws Exception {
        when(redisTemplate.execute(
                any(RedisScript.class),
                anyList(),
                any(), any(), any()
        )).thenReturn(1L);

        mockMvc.perform(post("/api/v2/bookings")
                        .header("X-USER-ID", "1")
                        .contentType("application/json")
                        .content("{\"concertId\":1,\"seatId\":10}"))
                .andExpect(status().isAccepted())
                .andExpect(content().string("좌석 선점 성공, 결제 진행해주세요"));
    }

    @Test
    @DisplayName("V2 예매: 이미 선점된 좌석이면 409 Conflict 반환")
    void createBookingV2_alreadyTaken() throws Exception {
        when(redisTemplate.execute(any(), anyList(), (Object[]) any())).thenReturn(0L);

        mockMvc.perform(post("/api/v2/bookings")
                        .header("X-USER-ID", "1")
                        .contentType("application/json")
                        .content("{\"concertId\":1,\"seatId\":10}"))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("V2 예매: 이미 예매 진행 중인 유저가 다른 좌석 요청하면 409 Conflict 반환")
    void createBookingV2_duplicateUserBooking() throws Exception {
        // booking:result:{userId} 가 이미 존재 → Lua Script가 0 반환
        when(redisTemplate.execute(any(), anyList(), (Object[]) any())).thenReturn(0L);

        mockMvc.perform(post("/api/v2/bookings")
                        .header("X-USER-ID", "1")
                        .contentType("application/json")
                        .content("{\"concertId\":1,\"seatId\":20}"))
                .andExpect(status().isConflict());
    }

    // ── V1 예매 ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("V1 예매: 좌석이 가용하면 202 Accepted 반환")
    void createBookingV1_success() throws Exception {
        when(setOps.isMember(anyString(), anyString())).thenReturn(true);

        mockMvc.perform(post("/api/v1/bookings")
                        .header("X-USER-ID", "1")
                        .contentType("application/json")
                        .content("{\"concertId\":1,\"seatId\":10}"))
                .andExpect(status().isAccepted())
                .andExpect(content().string("예약 요청이 접수되었습니다."));
    }

    @Test
    @DisplayName("V1 예매: 이미 선점된 좌석이면 409 Conflict 반환")
    void createBookingV1_alreadyTaken() throws Exception {
        when(setOps.isMember(anyString(), anyString())).thenReturn(false);

        mockMvc.perform(post("/api/v1/bookings")
                        .header("X-USER-ID", "1")
                        .contentType("application/json")
                        .content("{\"concertId\":1,\"seatId\":10}"))
                .andExpect(status().isConflict());
    }

    // ── 인터셉터 ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("인터셉터: active_user 토큰 없으면 403 Forbidden")
    void interceptor_blocksUnauthorizedUser() throws Exception {
        when(redisTemplate.hasKey("active_user:999")).thenReturn(false);

        mockMvc.perform(post("/api/v2/bookings")
                        .header("X-USER-ID", "999")
                        .contentType("application/json")
                        .content("{\"concertId\":1,\"seatId\":10}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("인터셉터: X-USER-ID 헤더 없으면 400 Bad Request")
    void interceptor_missingHeader_returns400() throws Exception {
        mockMvc.perform(post("/api/v2/bookings")
                        .contentType("application/json")
                        .content("{\"concertId\":1,\"seatId\":10}"))
                .andExpect(status().isBadRequest());
    }

    // ── 좌석 조회 (인터셉터 미적용 경로) ────────────────────────────────

    @Test
    @DisplayName("좌석 조회: 가용 좌석이 있으면 목록 반환")
    void getAvailableSeats_returnsList() throws Exception {
        when(setOps.members("concert:1:available")).thenReturn(Set.of("10", "11", "12"));

        mockMvc.perform(get("/api/seats/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3));
    }

    @Test
    @DisplayName("좌석 조회: 가용 좌석이 없으면 204 No Content 반환")
    void getAvailableSeats_empty() throws Exception {
        when(setOps.members(anyString())).thenReturn(Set.of());

        mockMvc.perform(get("/api/seats/1"))
                .andExpect(status().isNoContent());
    }

    // ── 예매 상태 조회 (인터셉터 미적용 경로) ────────────────────────────

    @Test
    @DisplayName("상태 조회: PAYMENT_PENDING이면 SUCCESS 반환")
    void getBookingStatus_paymentPending() throws Exception {
        when(valueOps.get("booking:result:1")).thenReturn("PAYMENT_PENDING");

        mockMvc.perform(get("/api/status").header("X-USER-ID", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"));
    }

    @Test
    @DisplayName("상태 조회: 결과 없으면 WAITING 반환")
    void getBookingStatus_waiting() throws Exception {
        when(valueOps.get("booking:result:1")).thenReturn(null);

        mockMvc.perform(get("/api/status").header("X-USER-ID", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("WAITING"));
    }

    @Test
    @DisplayName("상태 조회: CANCELLED이면 FAIL 반환")
    void getBookingStatus_cancelled() throws Exception {
        when(valueOps.get("booking:result:1")).thenReturn("CANCELLED");

        mockMvc.perform(get("/api/status").header("X-USER-ID", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAIL"));
    }
}
