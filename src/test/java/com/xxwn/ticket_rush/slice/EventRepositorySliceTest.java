package com.xxwn.ticket_rush.slice;

import com.xxwn.ticket_rush.domain.event.Event;
import com.xxwn.ticket_rush.domain.event.EventRepository;
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
class EventRepositorySliceTest {

    @Autowired EventRepository concertRepository;
    @Autowired TestEntityManager em;

    @Test
    @DisplayName("공연 저장 후 ID가 생성된다")
    void save_generatesId() {
        Event event = Event.builder()
                .title("Event A")
                .startTime(LocalDateTime.of(2026, 6, 1, 18, 0))
                .endTime(LocalDateTime.of(2026, 6, 1, 21, 0))
                .build();

        Event saved = concertRepository.save(event);

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getTitle()).isEqualTo("Event A");
    }

    @Test
    @DisplayName("저장된 공연을 ID로 조회할 수 있다")
    void findById_returnsCorrectEvent() {
        Event event = Event.builder()
                .title("Event B")
                .startTime(LocalDateTime.of(2026, 7, 1, 19, 0))
                .endTime(LocalDateTime.of(2026, 7, 1, 22, 0))
                .build();
        concertRepository.save(event);
        em.flush();
        em.clear();

        Event found = concertRepository.findById(event.getId()).orElseThrow();

        assertThat(found.getTitle()).isEqualTo("Event B");
        assertThat(found.getStartTime()).isEqualTo(LocalDateTime.of(2026, 7, 1, 19, 0));
    }
}
