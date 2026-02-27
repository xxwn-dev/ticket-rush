# 고동시성 콘서트 티켓 예매 시스템

> 수천 명의 동시 예매 요청을 이중 예약 없이 처리하는 프로덕션 수준의 티켓팅 백엔드

---

## 개요

콘서트 티켓팅의 핵심 문제를 해결하는 Spring Boot 백엔드입니다. 수천 명이 제한된 좌석을 동시에 예매하려 할 때 레이스 컨디션이나 이중 예약 없이 처리합니다.

**핵심 과제**: 2,000명 이상의 사용자가 동시에 같은 좌석을 예매하려 할 때, 정확히 한 명만 성공하도록 보장하려면?

---

## 기술 스택

| 레이어 | 기술 |
|-------|-----------|
| 런타임 | Java 21 (Virtual Threads) |
| 프레임워크 | Spring Boot 3.5 |
| 데이터베이스 | MySQL 8 + Spring Data JPA |
| 캐시 · 분산 조율 | Redis (Sentinel HA 모드) |
| 메시지 큐 | RabbitMQ |
| 분산 락 | Redisson |
| 모니터링 | Prometheus + Grafana |
| 부하 테스트 | k6 |
| 인프라 | Docker Compose |

---

## 아키텍처

```
사용자 요청
    │
    ▼
QueueInterceptor ──── (토큰 없음 → 403)
    │
    ▼
BookingController
    │
    ├── V2: Atomic Lua Script (SREM + SETEX) ──→ RabbitMQ ──→ BookingConsumer ──→ MySQL
    │                                                                               ▲
    └── V1: Redisson 분산 락 ────────────────────→ RabbitMQ ──→ BookingConsumer ───┘

QueueScheduler (매 500ms)
    └── Redis Pipeline: 최대 2,000명 승격 → Pub/Sub 브로드캐스트 → SSE → 클라이언트
```

---

## 핵심 기능

### 1. 이중 예약 방지 전략 (두 가지 방식)

**V2 — Atomic Lua Script** (권장 경로)
```lua
-- 단일 Redis 작업: 가용성 확인 + 예약을 한 번에 원자적으로 처리
if redis.call('SREM', KEYS[1], ARGV[1]) == 1 then
    redis.call('SETEX', KEYS[2], ARGV[3], ARGV[2])
    return 1  -- 성공
else
    return 0  -- 이미 예약됨
end
```
분산 락이 필요 없습니다. Redis 단일 스레드 실행 모델로 원자성을 보장합니다.

**V1 — Redisson 분산 락**
```java
RLock lock = redissonClient.getLock("lock:seat:" + seatId);
if (lock.tryLock(1, 3, TimeUnit.SECONDS)) {
    // 안전 구간: 확인 → Redis 감소 → DB 쓰기
}
```
좌석별 락 (대기 1초 / 유지 3초). 명시적이고 디버깅이 용이합니다.

### 2. 공정 대기열

사용자는 입장 타임스탬프를 점수로 사용하는 Redis Sorted Set에 합류합니다. 매 500ms마다 스케줄러가 **단일 Redis Pipeline**으로 최대 2,000명을 승격합니다. 사용자당 `SETEX + ZREM + PUBLISH` 3개 명령을 하나의 네트워크 왕복으로 처리합니다.

승격된 사용자는 **SSE(Server-Sent Events)**로 실시간 알림을 받습니다. 인스턴스 간 전달은 **Redis Pub/Sub**으로 처리합니다. 각 서버 인스턴스가 자신에게 연결된 클라이언트만 관리하므로 수평 확장이 자연스럽게 지원됩니다.

### 3. RabbitMQ를 통한 비동기 예매

예매 요청은 즉시 큐에 적재되어 빠른 HTTP 응답을 반환하고, 이후 컨슈머가 비동기로 처리합니다. 사용자 응답 지연과 실제 DB 쓰기를 분리합니다.

RabbitMQ 컨슈머는 **플랫폼 스레드**로 실행합니다 (가상 스레드 제외). AMQP 클라이언트의 `synchronized` 블록이 가상 스레드를 고정(pinning)시키는 실제 JVM 문제를 방지하기 위해서입니다.

### 4. 시작 시 Redis 워밍업

`DataInitializer`가 기동 시 2,000개의 가용 좌석 ID를 Redis Set에 미리 로드합니다. Lua 스크립트는 이 캐시를 읽어 핫 패스에서 DB 부하를 제거합니다.

---

## 성능

> 테스트 환경: 로컬 머신 (MacBook), Docker (MySQL + Redis + RabbitMQ), k6 stress test — 최대 2,000 VU, 4분간 실행

| 지표 | 목표 | 실측값 | 결과 |
|------|------|--------|------|
| p95 예매 지연 | < 3,000ms | **14.44ms** | ✓ 목표 대비 200배 초과 달성 |
| p95 대기열 진입 | < 200ms | **11.71ms** | ✓ 목표 대비 17배 초과 달성 |
| p95 SSE 지연 | < 3,000ms | **13.93ms** | ✓ 목표 대비 215배 초과 달성 |
| 인프라 오류율 | < 5% | **0.00%** | ✓ |
| 처리량 | — | **1,526 req/s** | 2,000 VU 기준 |
| 전체 체크 성공률 | — | **100%** (185,489건) | ✓ |

**HTTP 실패율 16.05%에 대해**: 인프라 오류는 0%. 이 실패는 전부 **409 Conflict** 응답으로, 이미 예약된 좌석을 정상적으로 거부한 결과입니다. 이중 예약 방지 로직이 올바르게 동작한 것입니다.

> 로컬 환경 특성상 네트워크 레이턴시가 없어 지연 수치가 낮게 측정됩니다. 실제 분산 배포 환경에서는 수치가 올라가지만, 처리량과 오류율 0%는 유효한 지표입니다.

**동시성 테스트**: 200개 스레드가 같은 좌석을 동시에 예매 시도. 정확히 1건만 성공. DB(`bookingRepository.count() == 1`)와 Redis(좌석이 가용 Set에서 제거됨) 양쪽에서 검증합니다.

---

## 주요 설계 결정

**가상 스레드 전역 적용 — RabbitMQ 컨슈머 제외**
Java 21 가상 스레드는 전역으로 활성화하되, MQ 컨슈머는 전용 플랫폼 스레드 풀(20–50개)을 사용합니다. AMQP 클라이언트의 `synchronized` 블록이 가상 스레드를 고정시켜 이점을 상쇄하기 때문입니다.

**낙관적 잠금 대신 Lua 스크립트**
DB 낙관적 잠금(버전 컬럼)은 충돌 시 재시도 로직이 필요하고 여전히 DB에 접근합니다. Redis Lua 스크립트는 캐시 레이어에서 확인과 예약을 원자적으로 처리하므로, DB 쓰기는 성공 경로에서만 발생합니다.

**대량 승격을 위한 Pipeline**
2,000명을 개별 처리하면 Redis 왕복 2,000회. `executePipelined()`로 모든 작업을 단일 왕복으로 배치 처리하여 네트워크 오버헤드를 약 99.9% 절감합니다.

**수평 SSE 전달을 위한 Pub/Sub**
인메모리 SSE 에미터는 인스턴스 간 공유가 불가합니다. 각 인스턴스가 Redis Pub/Sub 채널을 구독하고, 승격 이벤트 수신 시 자신의 에미터 맵을 확인합니다. 단순하고, 상태 비저장이며, 수평 확장에 적합합니다.

---

## 인프라

```
MySQL 8          :3306  — 예매 데이터 영속성
Redis Master     :6379  — 캐시 + 분산 조율
Redis Replica    :6380  — 장애 조치 대기
Redis Sentinels  :26379-26381 — HA 모니터링
RabbitMQ         :5672  — 비동기 예매 큐
Prometheus       :9090  — 메트릭 수집
Grafana          :3000  — 대시보드
```

Sentinel 장애 조치 수동 테스트: 마스터 컨테이너 중단 → 자동 리더 선출 → 센티넬 로그로 확인 → 복구 후 레플리카로 재합류.

---

## 프로젝트 구조

```
src/main/java/com/xxwn/ticketing_app/
├── domain/
│   ├── booking/      BookingController, BookingService, RedisLockFacade, BookingConsumer
│   ├── queue/        QueueController, QueueScheduler, QueueInterceptor, WaitingQueueService
│   └── seat/         SeatService (Redis 워밍업)
├── config/           RabbitMQConfig, RedisConfig, AsyncConfig, DataInitializer
└── global/sse/       SseService, QueuePromotionListener

load-test/
├── booking-stress.js   피크 2,000 VU 부하 테스트
├── booking-test-v2.js  V2 원자적 경로 검증
└── booking-soak.js     지속 부하 테스트
```

---

## 로컬 실행

```bash
# 인프라 시작
docker-compose up -d mysql redis-master rabbitmq

# 앱 실행 (로컬 프로파일: 단일 Redis, Sentinel 없음)
./gradlew bootRun --args='--spring.profiles.active=local'

# 동시성 테스트
./gradlew test --tests "com.xxwn.ticketing_app.ConcurrencyTest"

# k6 부하 테스트
k6 run load-test/booking-stress.js
```
