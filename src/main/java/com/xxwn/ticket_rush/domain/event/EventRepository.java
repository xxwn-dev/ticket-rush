package com.xxwn.ticket_rush.domain.event;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface EventRepository extends JpaRepository<Event, Long> {
    @Query("SELECT e FROM Event e WHERE e.startTime > :now ORDER BY e.startTime ASC, e.homeTeam ASC")
    List<Event> findAllByStartTimeAfter(@Param("now") LocalDateTime now);

    @Query("SELECT e.id FROM Event e WHERE e.endTime < :now")
    List<Long> findIdsByEndTimeBefore(@Param("now") LocalDateTime now);

    boolean existsByTitleAndStartTime(String title, LocalDateTime startTime);
}
