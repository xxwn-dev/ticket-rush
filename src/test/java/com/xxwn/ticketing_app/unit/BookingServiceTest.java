package com.xxwn.ticketing_app.unit;

import com.xxwn.ticketing_app.domain.booking.*;
import com.xxwn.ticketing_app.domain.concert.Concert;
import com.xxwn.ticketing_app.domain.seat.Seat;
import com.xxwn.ticketing_app.domain.seat.SeatRepository;
import com.xxwn.ticketing_app.domain.seat.SeatStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BookingServiceTest {

    @Mock BookingRepository bookingRepository;
    @Mock SeatRepository seatRepository;
    @Mock StringRedisTemplate redisTemplate;
    @Mock ValueOperations<String, String> valueOps;

    @InjectMocks BookingService bookingService;

    private Concert concert;
    private Seat seat;

    @BeforeEach
    void setUp() {
        concert = Concert.builder()
                .title("Test Concert")
                .startTime(LocalDateTime.now().plusDays(1))
                .endTime(LocalDateTime.now().plusDays(1).plusHours(3))
                .build();
        seat = Seat.builder()
                .seatNumber("A1")
                .status(SeatStatus.AVAILABLE)
                .concert(concert)
                .build();
    }

    // ── createBooking ────────────────────────────────────────────────────

    @Test
    @DisplayName("createBooking: 좌석이 존재하면 예약이 저장된다")
    void createBooking_success() {
        when(seatRepository.findByIdAndConcertId(1L, 1L)).thenReturn(Optional.of(seat));
        when(bookingRepository.save(any())).thenReturn(Booking.create(1L, seat));

        assertThatCode(() -> bookingService.createBooking(1L, 1L, 1L))
                .doesNotThrowAnyException();
        verify(bookingRepository).save(any(Booking.class));
    }

    @Test
    @DisplayName("createBooking: 존재하지 않는 좌석이면 IllegalArgumentException 발생")
    void createBooking_seatNotFound() {
        when(seatRepository.findByIdAndConcertId(99L, 1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> bookingService.createBooking(1L, 99L, 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Wrong Seat id");
    }

    // ── processV2Booking ─────────────────────────────────────────────────

    @Test
    @DisplayName("processV2Booking: 성공 시 Redis에 PAYMENT_PENDING이 저장된다")
    void processV2Booking_success() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(seatRepository.findByIdAndConcertId(1L, 1L)).thenReturn(Optional.of(seat));
        when(bookingRepository.save(any())).thenReturn(Booking.create(1L, seat));

        bookingService.processV2Booking(1L, 1L, 1L);

        verify(valueOps).set(eq("booking:result:1"), eq("PAYMENT_PENDING"), eq(600L), eq(TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("processV2Booking: 존재하지 않는 좌석이면 예외 발생")
    void processV2Booking_seatNotFound() {
        when(seatRepository.findByIdAndConcertId(99L, 1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> bookingService.processV2Booking(1L, 99L, 1L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ── processCancel ────────────────────────────────────────────────────

    @Test
    @DisplayName("processCancel: 예약이 존재하면 취소 후 Redis에 CANCELLED가 저장된다")
    void processCancel_success() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(seatRepository.findByIdAndConcertId(1L, 1L)).thenReturn(Optional.of(seat));
        when(bookingRepository.deleteByUserIdAndSeatId(1L, 1L)).thenReturn(1L);

        bookingService.processCancel(1L, 1L, 1L);

        verify(valueOps).set(eq("booking:result:1"), eq("CANCELLED"), eq(600L), eq(TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("processCancel: 이미 취소된 예약은 Redis 상태 변경 없이 경고만 남긴다")
    void processCancel_alreadyCancelled() {
        when(seatRepository.findByIdAndConcertId(1L, 1L)).thenReturn(Optional.of(seat));
        when(bookingRepository.deleteByUserIdAndSeatId(1L, 1L)).thenReturn(0L);

        bookingService.processCancel(1L, 1L, 1L);

        verify(valueOps, never()).set(any(), any(), anyLong(), any());
    }
}
