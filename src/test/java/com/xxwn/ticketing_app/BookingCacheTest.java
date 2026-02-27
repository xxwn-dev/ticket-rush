package com.xxwn.ticketing_app;

import com.xxwn.ticketing_app.domain.booking.BookingConsumer;
import com.xxwn.ticketing_app.domain.booking.BookingMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
public class BookingCacheTest {

    @Autowired
    private BookingConsumer bookingConsumer;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Test
    @DisplayName("SREM을 통한 선점 성공시 Redis에서 좌석이 제거되어야 한다.")
    void sremSuccessTest(){

        Long concertId = 1L;
        Long seatId = 10L;
        String key = "concert:" + concertId + ":available";
        redisTemplate.opsForSet().add(key, seatId.toString());

        BookingMessage message = new BookingMessage(1L, seatId, concertId);
        bookingConsumer.receiveV1Message(message);

        Boolean isExist = redisTemplate.opsForSet().isMember(key, seatId.toString());
        assertThat(isExist).isFalse();
    }

    @Test
    @DisplayName("이미 선점된 좌석은 SREM 결과가 0이 되어 실패 처리되어야 한다")
    void reservedSeatSremFailTest(){
        String key = "concert:1:available";
        redisTemplate.delete(key);

        BookingMessage message = new BookingMessage(1L, 10L, 1L);
        bookingConsumer.receiveV1Message(message);
    }
}
