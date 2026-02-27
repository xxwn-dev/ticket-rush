package com.xxwn.ticketing_app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class TicketingAppApplication {

	public static void main(String[] args) {
		SpringApplication.run(TicketingAppApplication.class, args);
	}

}
