package com.xxwn.ticket_rush.domain.admin;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;

@Component
@RequiredArgsConstructor
@Slf4j
public class KboScheduler {

    private final AdminEventService adminEventService;

    @Scheduled(cron = "0 0 0 * * *", zone = "Asia/Seoul")
    public void syncSchedule() {
        log.info("KBO 스케줄러 시작");
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        adminEventService.importMonth(today.getYear(), today.getMonthValue());
        LocalDate next = today.plusMonths(1);
        adminEventService.importMonth(next.getYear(), next.getMonthValue());
        adminEventService.deletePastEvents();
        log.info("KBO 스케줄러 완료");
    }
}
