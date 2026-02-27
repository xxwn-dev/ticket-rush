package com.xxwn.ticketing_app;

import com.xxwn.ticketing_app.domain.concert.Concert;
import com.xxwn.ticketing_app.domain.concert.ConcertRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
public class ConcertRepositoryTest {

    @Autowired
    private ConcertRepository concertRepository;

    @Test
    void 공연_정보를_저장하고_확인한다(){
        Concert concert = Concert.builder()
                .title("Concert A")
                .startTime(LocalDateTime.of(2026, 6,1, 18, 0))
                .endTime(LocalDateTime.of(2026, 6,1,21,0))
                .build();
        Concert savedConcert = concertRepository.save(concert);
        assertThat(savedConcert.getId()).isNotNull();
        assertThat(savedConcert.getTitle()).isEqualTo("Concert A");
    }
}
