package com.xxwn.ticketing_app.slice;

import com.xxwn.ticketing_app.domain.concert.Concert;
import com.xxwn.ticketing_app.domain.concert.ConcertRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class ConcertRepositorySliceTest {

    @Autowired ConcertRepository concertRepository;
    @Autowired TestEntityManager em;

    @Test
    @DisplayName("공연 저장 후 ID가 생성된다")
    void save_generatesId() {
        Concert concert = Concert.builder()
                .title("Concert A")
                .startTime(LocalDateTime.of(2026, 6, 1, 18, 0))
                .endTime(LocalDateTime.of(2026, 6, 1, 21, 0))
                .build();

        Concert saved = concertRepository.save(concert);

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getTitle()).isEqualTo("Concert A");
    }

    @Test
    @DisplayName("저장된 공연을 ID로 조회할 수 있다")
    void findById_returnsCorrectConcert() {
        Concert concert = Concert.builder()
                .title("Concert B")
                .startTime(LocalDateTime.of(2026, 7, 1, 19, 0))
                .endTime(LocalDateTime.of(2026, 7, 1, 22, 0))
                .build();
        concertRepository.save(concert);
        em.flush();
        em.clear();

        Concert found = concertRepository.findById(concert.getId()).orElseThrow();

        assertThat(found.getTitle()).isEqualTo("Concert B");
        assertThat(found.getStartTime()).isEqualTo(LocalDateTime.of(2026, 7, 1, 19, 0));
    }
}
