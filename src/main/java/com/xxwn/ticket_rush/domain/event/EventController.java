package com.xxwn.ticket_rush.domain.event;

import com.xxwn.ticket_rush.domain.seat.SeatRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/events")
@RequiredArgsConstructor
public class EventController {

    private final EventRepository eventRepository;
    private final SeatRepository seatRepository;
    private final StringRedisTemplate redisTemplate;

    @GetMapping
    public ResponseEntity<List<EventResponse>> list() {
        List<EventResponse> events = eventRepository
                .findAllByStartTimeAfter(LocalDateTime.now(ZoneId.of("Asia/Seoul"))).stream()
                .map(event -> {
                    long total = seatRepository.countByEventId(event.getId());
                    long available = availableCount(event.getId());
                    return EventResponse.of(event, total, available);
                })
                .toList();
        return ResponseEntity.ok(events);
    }

    @GetMapping("/{id}/seats/available")
    public ResponseEntity<Map<String, Object>> availableSeats(@PathVariable Long id) {
        long count = availableCount(id);
        return ResponseEntity.ok(Map.of("eventId", id, "availableCount", count));
    }

    private long availableCount(Long eventId) {
        Long size = redisTemplate.opsForSet().size("event:" + eventId + ":available");
        return size != null ? size : 0L;
    }
}
