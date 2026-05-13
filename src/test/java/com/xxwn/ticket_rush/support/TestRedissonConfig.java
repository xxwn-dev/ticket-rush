package com.xxwn.ticket_rush.support;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Testcontainers 환경에서 Redisson을 동적 포트에 연결하기 위한 테스트 전용 설정.
 *
 * 왜 이 클래스가 필요한가?
 * - Redisson은 spring.data.redis.host/port를 무시하고 redisson-local.yaml을 직접 읽는다.
 * - Testcontainers는 Redis를 랜덤 포트로 기동하므로, 런타임에 주소를 알 수 있다.
 * - AbstractIntegrationTest의 @DynamicPropertySource가 spring.data.redis.host/port를 TC 값으로 세팅하면,
 *   이 빈이 그 값을 읽어 RedissonClient를 생성한다.
 * - RedissonAutoConfiguration은 @ConditionalOnMissingBean(RedissonClient.class)이므로,
 *   이 빈이 먼저 등록되면 자동 구성이 자동으로 스킵된다.
 */
@TestConfiguration
public class TestRedissonConfig {

    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient(
            @Value("${spring.data.redis.host}") String host,
            @Value("${spring.data.redis.port}") int port) {

        Config config = new Config();
        config.setThreads(4);
        config.setNettyThreads(8);
        config.useSingleServer()
              .setAddress("redis://" + host + ":" + port)
              .setDatabase(0);

        return Redisson.create(config);
    }
}
