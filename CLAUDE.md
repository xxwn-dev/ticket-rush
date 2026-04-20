# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

```bash
# ── 로컬 개발 (Mac 호스트에서 Spring Boot 직접 실행) ──────────────────────────
# Sentinel 없이 단일 Redis로 실행 (Mac Docker Desktop networking 우회)
docker-compose up -d mysql redis-master rabbitmq
./gradlew bootRun --args='--spring.profiles.active=local'

# ── 전체 Docker 스택 (Sentinel + 멀티 인스턴스) ──────────────────────────────
docker-compose --profile full up -d              # app1 + app2 + nginx + Sentinel 전체

# ── 빌드 ───────────────────────────────────────────────────────────────────────
./gradlew build

# ── 테스트 (application-test.yaml이 Sentinel을 단일 Redis로 우회) ──────────────
docker-compose up -d mysql redis-master rabbitmq
./gradlew test
./gradlew test --tests "com.xxwn.ticketing_app.integration.ConcurrencyTest"

# ── k6 부하테스트 (로컬 프로파일로 Spring Boot 실행 후) ───────────────────────
k6 run load-test/booking-stress.js               # thresholds 있음 (p95<3s, fail<5%)
k6 run load-test/booking-test-v2.js
k6 run load-test/booking-soak.js

# ── 멀티 인스턴스 검증 (docker --profile full 실행 후) ───────────────────────
k6 run load-test/multi-instance-test.js          # 300 VU, nginx round-robin

# ── Sentinel Failover 수동 테스트 ─────────────────────────────────────────────
docker stop ticket-redis-master                  # 마스터 강제 종료
docker logs ticket-redis-sentinel-1 | grep switch-master   # failover 확인
docker start ticket-redis-master                 # 복구 후 replica로 합류
```

## Infrastructure

The app requires MySQL, Redis, and RabbitMQ — all defined in `docker-compose.yaml`:
- MySQL on `3306` (db: `ticket_db`, root/root)
- Redis on `6379`
- RabbitMQ on `5672`, management UI on `15672` (guest/guest)
- Prometheus on `9090`, Grafana on `3000`

`DataInitializer` runs on startup (non-test profile) and wipes+recreates test data: 1 concert + 2000 seats, then warms up the Redis seat cache.

## Project Structure

```
src/main/java/com/xxwn/ticketing_app/
├── TicketingAppApplication.java
├── config/
│   ├── AsyncConfig.java          # sseTaskExecutor bean (VirtualThreadTaskExecutor)
│   ├── DataInitializer.java      # Startup data seeding + Redis warmup (non-test profile)
│   ├── RabbitMQConfig.java       # Exchange/queue/binding definitions + MQ thread pool
│   ├── RedisConfig.java          # RedisMessageListenerContainer (Pub/Sub subscriber)
│   └── WebConfig.java            # Registers QueueInterceptor on /api/**
├── domain/
│   ├── booking/
│   │   ├── Booking.java
│   │   ├── BookingConsumer.java   # RabbitMQ listeners for V1, V2, and cancel queues
│   │   ├── BookingController.java # REST endpoints + Lua script logic
│   │   ├── BookingMessage.java    # Record: userId, seatId, concertId
│   │   ├── BookingRepository.java
│   │   ├── BookingService.java    # DB-side booking/cancel logic
│   │   ├── BookingStatus.java     # Enum: PAYMENT_PENDING, CONFIRMED, CANCELLED, REJECTED
│   │   └── RedisLockFacade.java   # Redisson lock + Redis cache decrement (V1)
│   ├── concert/
│   │   ├── Concert.java
│   │   └── ConcertRepository.java
│   ├── queue/
│   │   ├── QueueController.java   # SSE subscribe + queue register endpoints
│   │   ├── QueueInterceptor.java  # Validates active_user: token on every /api/ request
│   │   ├── QueueResponse.java     # Record: rank, remaining, status string
│   │   ├── QueueScheduler.java    # @Scheduled every 500ms: promotes users, sends SSE updates
│   │   └── WaitingQueueService.java # Adds userId to Redis ZSet with timestamp score
│   └── seat/
│       ├── Seat.java
│       ├── SeatRepository.java
│       ├── SeatService.java       # warmupSeats(): loads available seat IDs into Redis Set
│       └── SeatStatus.java        # Enum: AVAILABLE, RESERVED
└── global/
    ├── error/
    │   ├── ErrorResponse.java
    │   └── GlobalExceptionHandler.java
    └── sse/
        ├── SseController.java
        ├── SseService.java           # In-memory ConcurrentHashMap of SseEmitter per userId
        └── QueuePromotionListener.java  # Redis Pub/Sub MessageListener → triggers SSE GO_BOOKING

src/test/java/com/xxwn/ticketing_app/
├── BookingCacheTest.java
├── BookingControllerTest.java
├── ConcertRepositoryTest.java
├── ConcurrencyTest.java           # 200-thread concurrent booking test with RedisLockFacade
├── TicketingAppApplicationTests.java
└── WaitingQueueTest.java

load-test/                         # k6 scripts
├── booking-test.js                # V1 booking load test
├── booking-test-v2.js             # V2 booking load test
├── booking-stress.js
└── booking-soak.js
```

## Architecture

This is a high-concurrency ticketing system built with Spring Boot 3.5 + Java 21 virtual threads. The core challenge is preventing double-booking under massive concurrent load.

### Booking Flow

Two versioned strategies exist side-by-side:

**V1** (`POST /api/v1/bookings`): Optimistic pre-check → RabbitMQ queue → `BookingConsumer` → `RedisLockFacade` (Redisson distributed lock per seat) → Redis seat cache decrement → DB write.

**V2** (`POST /api/v2/bookings`): Atomic Lua script on Redis (SREM + SETEX in one operation) → RabbitMQ queue → `BookingConsumer` → `BookingService.processV2Booking()` → DB write. No distributed lock needed; atomicity is guaranteed by Lua script.

**Cancellation** (`DELETE /api/v2/bookings`): Lua script to restore Redis seat + clear booking status → RabbitMQ cancel queue → DB delete.

### Redis Key Schema

| Key | Type | Purpose |
|-----|------|---------|
| `concert:{id}:available` | Set | Available seat IDs (whitelist) |
| `booking:result:{userId}` | String | Booking status: `PAYMENT_PENDING`, `CONFIRMED`, `CANCELLED`, `REJECTED` |
| `active_user:{userId}` | String | Active queue token (TTL 10min) |
| `concert:waiting_queue` | ZSet | Waiting queue ordered by timestamp |
| `lock:seat:{seatId}` | — | Redisson distributed lock (V1 only) |

### Waiting Queue

Users first join the queue via `POST /api/queue/join` (header: `X-USER-ID`). `QueueScheduler` runs every 500ms and promotes up to 2000 users using a **single Redis Pipeline** (SETEX + ZREM + PUBLISH per user — one network round-trip for all). The PUBLISH triggers `QueuePromotionListener` on every server instance via Redis Pub/Sub; each instance forwards the signal to its locally connected SSE clients (`SseService.waitingEmitters`). This design enables horizontal scaling.

`QueueInterceptor` blocks booking endpoints unless the user has a valid `active_user:` token (TTL 10 min). Booking status is polled via `GET /api/status` (header: `X-USER-ID`).

### RabbitMQ

Two exchanges: `booking.exchange` (V1/V2 booking) and `booking.cancel.exchange`. Three queues: `booking.v1.queue`, `booking.v2.queue`, `booking.cancel.queue`. The MQ connection factory uses a dedicated platform thread pool (100–200 threads) to avoid virtual thread pinning with the AMQP client.

### Key Design Decisions

- JPA DDL is set to `create` — the DB schema is rebuilt on every startup.
- Virtual threads are enabled globally (`spring.threads.virtual.enabled: true`), but RabbitMQ consumers run on platform threads to avoid pinning.
- SSE emitters are stored in an in-memory `ConcurrentHashMap` in `SseService`. Cross-instance signaling is handled by Redis Pub/Sub (`queue:promoted` channel) — each instance only delivers SSE to its own connected clients.
- `AsyncConfig` registers a `sseTaskExecutor` used specifically for `SseService.sendMoveSignal`.

## Skill routing

When the user's request matches an available skill, ALWAYS invoke it using the Skill
tool as your FIRST action. Do NOT answer directly, do NOT use other tools first.
The skill has specialized workflows that produce better results than ad-hoc answers.

Key routing rules:
- Product ideas, "is this worth building", brainstorming → invoke office-hours
- Bugs, errors, "why is this broken", 500 errors → invoke investigate
- Ship, deploy, push, create PR → invoke ship
- QA, test the site, find bugs → invoke qa
- Code review, check my diff → invoke review
- Update docs after shipping → invoke document-release
- Weekly retro → invoke retro
- Design system, brand → invoke design-consultation
- Visual audit, design polish → invoke design-review
- Architecture review → invoke plan-eng-review
- Save progress, checkpoint, resume → invoke checkpoint
- Code quality, health check → invoke health
