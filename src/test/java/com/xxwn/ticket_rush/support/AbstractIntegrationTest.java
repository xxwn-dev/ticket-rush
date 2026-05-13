package com.xxwn.ticket_rush.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.lifecycle.Startables;

/**
 * 통합 테스트 베이스 클래스 — Singleton Container Pattern.
 *
 * 왜 @Container 대신 static initializer를 쓰는가?
 * - @Container는 JUnit 5 TestcontainersExtension이 컨테이너를 시작한다.
 * - @SpringBootTest의 SpringExtension은 컨텍스트 생성 시 @DynamicPropertySource를 호출하는데,
 *   이 시점에 TestcontainersExtension의 beforeAll이 아직 실행되지 않았을 수 있다.
 * - 결과: @DynamicPropertySource 호출 시 컨테이너가 미기동 → getMappedPort() 실패 또는 잘못된 포트 반환.
 *
 * static initializer는 클래스 로딩 시 JVM이 직접 실행한다.
 * AbstractIntegrationTest를 상속한 테스트 클래스가 로드되는 순간 컨테이너가 기동 완료되므로,
 * @DynamicPropertySource 호출 시점에 항상 정상 포트를 반환할 수 있다.
 *
 * Startables.deepStart()로 세 컨테이너를 병렬 기동해 전체 대기 시간을 최소화한다.
 * 컨테이너 종료는 Testcontainers의 Ryuk reaper와 JVM shutdown hook이 처리한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("testcontainers")
@Import(TestRedissonConfig.class)
public abstract class AbstractIntegrationTest {

    static final MySQLContainer<?> mysql;
    static final GenericContainer<?> redis;
    static final RabbitMQContainer rabbitmq;

    static {
        mysql = new MySQLContainer<>("mysql:8.0")
                .withDatabaseName("ticket_db")
                .withUsername("root")
                .withPassword("root");
        redis = new GenericContainer<>("redis:7-alpine")
                .withExposedPorts(6379);
        rabbitmq = new RabbitMQContainer("rabbitmq:3-management");

        // 세 컨테이너를 병렬로 기동 — 순차 기동 대비 전체 시작 시간 단축
        Startables.deepStart(mysql, redis, rabbitmq).join();
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        // MySQL
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);

        // Redis — TestRedissonConfig이 이 값을 읽어 RedissonClient를 생성한다
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));

        // RabbitMQ
        registry.add("spring.rabbitmq.host", rabbitmq::getHost);
        registry.add("spring.rabbitmq.port", rabbitmq::getAmqpPort);
    }
}
