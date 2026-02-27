package com.xxwn.ticketing_app.config;

import com.xxwn.ticketing_app.domain.queue.QueueInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

    private final QueueInterceptor queueInterceptor;

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
