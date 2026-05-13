package com.xxwn.ticket_rush.config;

import com.xxwn.ticket_rush.global.sse.QueuePromotionListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import java.util.concurrent.Executors;

@Configuration
public class RedisConfig {

    // 대기열 입장 허가 이벤트 채널 — 모든 서버 인스턴스가 구독
    public static final String QUEUE_PROMOTED_CHANNEL = "queue:promoted";

    @Bean
    public RedisMessageListenerContainer redisMessageListenerContainer(
            RedisConnectionFactory connectionFactory,
            QueuePromotionListener queuePromotionListener) {

        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        // 메시지 수신 처리는 가상 스레드로 — 핀닝 없이 비차단 I/O 처리
        container.setTaskExecutor(Executors.newVirtualThreadPerTaskExecutor());
        container.addMessageListener(queuePromotionListener, new ChannelTopic(QUEUE_PROMOTED_CHANNEL));
        return container;
    }
}
