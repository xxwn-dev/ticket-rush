package com.xxwn.ticket_rush;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class TicketRushApplication {

	public static void main(String[] args) {
		SpringApplication.run(TicketRushApplication.class, args);
	}

}
