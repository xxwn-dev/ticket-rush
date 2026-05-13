package com.xxwn.ticket_rush.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class RabbitMQConfig {

    public static final String BOOKING_EXCHANGE = "booking.exchange";
    public static final String BOOKING_CANCEL_EXCHANGE = "booking.cancel.exchange";
    public static final String BOOKING_QUEUE_V1 = "booking.v1.queue";
    public static final String BOOKING_ROUTING_KEY_V1 = "booking.v1.routing.key";
    public static final String BOOKING_QUEUE_V2 = "booking.v2.queue";
    public static final String BOOKING_ROUTING_KEY_V2 = "booking.v2.routing.key";
    public static final String BOOKING_CANCEL_QUEUE = "booking.cancel.queue";
    public static final String BOOKING_CANCEL_ROUTING_KEY = "booking.cancel.routing.key";

    // 좌석 선점 타임아웃 (TTL + DLX)
    public static final String HOLD_EXCHANGE = "booking.hold.exchange";
    public static final String HOLD_ROUTING_KEY = "booking.hold.routing.key";
    public static final String HOLD_QUEUE = "booking.hold.queue";
    public static final String HOLD_DLX = "booking.hold.dlx";
    public static final String HOLD_EXPIRED_QUEUE = "booking.hold.expired.queue";
    public static final String HOLD_EXPIRED_ROUTING_KEY = "booking.hold.expired.routing.key";

    @Bean
    public Queue v1Queue() {return new Queue(BOOKING_QUEUE_V1);}

    @Bean
    public DirectExchange exchange() {return new DirectExchange(BOOKING_EXCHANGE);}

    @Bean
    public Binding v1Binding(Queue v1Queue, DirectExchange exchange){
        return BindingBuilder.bind(v1Queue).to(exchange).with(BOOKING_ROUTING_KEY_V1);
    }

    @Bean
    public Queue v2Queue() {return new Queue(BOOKING_QUEUE_V2);}

    @Bean
    public Binding v2Binding(Queue v2Queue, DirectExchange exchange){
        return BindingBuilder.bind(v2Queue).to(exchange).with(BOOKING_ROUTING_KEY_V2);
    }

    @Bean
    public Queue cancelQueue(){
        return new Queue(BOOKING_CANCEL_QUEUE, true);
    }

    @Bean
    public DirectExchange cancelExchange(){
        return new DirectExchange(BOOKING_CANCEL_EXCHANGE);
    }

    @Bean
    public Binding cancelBinding(Queue cancelQueue, DirectExchange cancelExchange){
        return BindingBuilder.bind(cancelQueue)
                .to(cancelExchange)
                .with(BOOKING_CANCEL_ROUTING_KEY);
    }

    @Bean
    public DirectExchange holdExchange() { return new DirectExchange(HOLD_EXCHANGE); }

    @Bean
    public Queue holdQueue() {
        return QueueBuilder.durable(HOLD_QUEUE)
                .withArgument("x-message-ttl", 600_000)
                .withArgument("x-dead-letter-exchange", HOLD_DLX)
                .withArgument("x-dead-letter-routing-key", HOLD_EXPIRED_ROUTING_KEY)
                .build();
    }

    @Bean
    public Binding holdBinding(Queue holdQueue, DirectExchange holdExchange) {
        return BindingBuilder.bind(holdQueue).to(holdExchange).with(HOLD_ROUTING_KEY);
    }

    @Bean
    public DirectExchange holdDlx() { return new DirectExchange(HOLD_DLX); }

    @Bean
    public Queue holdExpiredQueue() { return new Queue(HOLD_EXPIRED_QUEUE, true); }

    @Bean
    public Binding holdExpiredBinding(Queue holdExpiredQueue, DirectExchange holdDlx) {
        return BindingBuilder.bind(holdExpiredQueue).to(holdDlx).with(HOLD_EXPIRED_ROUTING_KEY);
    }

    @Bean
    public MessageConverter jackson2JsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    /**
     * @RabbitListener 컨슈머 전용 플랫폼 스레드 풀
     *
     * spring.threads.virtual.enabled=true 환경에서도 AMQP 클라이언트의
     * synchronized 블록으로 인한 가상 스레드 핀닝을 방지합니다.
     * SimpleMessageListenerContainer 가 이 빈을 우선 사용합니다.
     */
    @Bean
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            ConnectionFactory connectionFactory,
            MessageConverter jackson2JsonMessageConverter) {

        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(20);
        executor.setMaxPoolSize(50);
        executor.setThreadNamePrefix("mq-consumer-");
        executor.initialize(); // ThreadPoolTaskExecutor 는 기본이 플랫폼 스레드

        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(jackson2JsonMessageConverter);
        factory.setTaskExecutor(executor);
        return factory;
    }
}
