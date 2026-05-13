package com.xxwon.ticket_rush.config;

import com.xxwn.ticket_rush.domain.admin.DataResetService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
@Profile("!test & !testcontainers")
@ConditionalOnProperty(name = "app.init-data", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class DataInitializer {

    private final DataResetService dataResetService;

    @EventListener(ApplicationReadyEvent.class)
    public void init() {
        dataResetService.reset();
    }
}
