package com.xxwn.ticket_rush.domain.admin;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminController {

    private final DataResetService dataResetService;
    private final AdminEventService adminEventService;

    @Value("${admin.token}")
    private String adminToken;

    @PostMapping("/reset")
    public ResponseEntity<Map<String, Object>> reset(@RequestHeader("Authorization") String auth) {
        if (!("Bearer " + adminToken).equals(auth)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "Unauthorized"));
        }
        dataResetService.reset();
        return ResponseEntity.ok(Map.of("reset", true));
    }

    @PostMapping("/events/kbo")
    public ResponseEntity<Map<String, Object>> importKbo(
            @RequestHeader("Authorization") String auth,
            @RequestParam int year,
            @RequestParam int month) {
        if (!("Bearer " + adminToken).equals(auth)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "Unauthorized"));
        }
        List<Long> ids = adminEventService.importMonth(year, month);
        return ResponseEntity.ok(Map.of("imported", ids.size(), "eventIds", ids));
    }
}
