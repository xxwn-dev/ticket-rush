package com.xxwn.ticketing_app;

import com.xxwn.ticketing_app.domain.booking.BookingController;
import com.xxwn.ticketing_app.domain.booking.BookingService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(BookingController.class)
public class BookingControllerTest {
    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private BookingService bookingService;

    @Test
    void 좌석이_이미_선점된_경우_400_에러와_메시지를_반환한다() throws Exception {
        // Given: 서비스에서 예외가 발생하는 상황 시뮬레이션
        doThrow(new IllegalStateException("이미 선택된 좌석입니다."))
                .when(bookingService).createBooking(anyLong(), anyLong(), anyLong());

        // When & Then: 호출 후 JSON 응답 검증
        mockMvc.perform(post("/api/bookings")
                        .param("userId", "1")
                        .param("seatId", "1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("Already_reserved"))
                .andExpect(jsonPath("$.message").value("이미 선택된 좌석입니다."));
    }
}
