package com.xxwn.ticket_rush.domain.admin;

import com.xxwn.ticket_rush.domain.admin.DataResetService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminController {

    private final DataResetService dataResetService;

    @Value("${admin.token}")
    private String adminToken;

    @PostMapping("/reset")
    public ResponseEntity<Map<String, Object>> reset(@RequestHeader("Authorization") String auth) {
        if (!("Bearer " + adminToken).equals(auth)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "Unauthorized"));
        }
        Long eventId = dataResetService.reset();
        return ResponseEntity.ok(Map.of("reset", true, "eventId", eventId));
    }
}
