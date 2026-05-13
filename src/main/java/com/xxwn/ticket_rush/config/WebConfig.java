package com.xxwn.ticket_rush.config;

import com.xxwn.ticket_rush.domain.queue.QueueInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

    private final QueueInterceptor queueInterceptor;

    @Value("${cors.allowed-origins:http://localhost:5173}")
    private String allowedOrigins;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(allowedOrigins.split(","))
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .exposedHeaders("*")
                .maxAge(3600);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry){
        registry.addInterceptor(queueInterceptor)
                .addPathPatterns("/api/bookings/**","/api/v1/bookings/**", "/api/v2/bookings/**")
                .excludePathPatterns(
                        "/api/queue/**",
                        "/api/subscribe/**",
                        "/api/test/**"
                );
    }
}
