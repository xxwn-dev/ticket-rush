# Design: ticket-rush Demo Deploy

Date: 2026-05-12
Status: APPROVED
Branch: rabbitmq

## Problem Statement

고동시성 티켓팅 백엔드(ticket-rush)는 기술적으로 완성도가 높지만, 면접관이 직접 URL을 받아 체험할 수 있는 라이브 데모가 없다. 파라미터 튜닝 + EC2 배포로 "면접관이 봇들과 직접 경쟁하는 티켓팅 데모"를 완성한다.

## Portfolio Context

- 대상: 4년 경력, 주니어/미들 백엔드 포지션 지원
- 데모 방식: URL 링크 공유 → 면접관이 직접 플레이
- 부하 증명: k6 결과 (p95 14.44ms @ 2000 VU)는 GitHub README에서 담당

## Design Decisions

### 1. 파라미터 전략 — 데모와 부하테스트 역할 분리

**핵심 원칙:** 데모는 긴장감 제공(UX), 부하 증명은 k6 결과(숫자). 두 가지를 한 곳에서 해결하려 하지 않는다.

**목표값:**
- 대기 시간: 12-15초 (긴장감은 주되 데모가 끊기지 않을 길이)
- 성공 확률: ~66% (이길 수도 질 수도 — 드라마)
- t2.micro 안전: Virtual Thread 150개 (IO-bound + 1초 sleep → 메모리 부담 미미)

**파라미터 계산:**
```
평균 순번 = BOT_COUNT / 2 = 75위
대기 시간 = ceil(75 / ALLOW_COUNT) × 0.5초 = ceil(75/3) × 0.5 = 12.5초
성공 확률 = seatCount / (BOT_COUNT + 1) = 100 / 151 ≈ 66%
```

**QueueInterceptor가 자연 throttle 역할 수행:**
- 150봇이 1초 후 동시 예매 시도
- active_user 토큰 있는 3봇만 Lua script 실행
- 나머지 147봇은 QueueInterceptor에서 즉시 403 (fast-fail)
- 실제 동시 예매 요청 = ALLOW_COUNT(3)개

| 파라미터 | 현재 | 변경 |
|---------|------|------|
| `BOT_COUNT` | 30 | **150** |
| `ALLOW_COUNT` | 2000 | **3** |
| `seatCount` (DataInitializer) | 2000 | **100** |

### 2. OOM 보호 — 모니터링 스택 제거

EC2 t2.micro (1GB RAM). 모니터링 서비스 포함 시 총 ~1,428m → OOM 위험.

docker-compose.yaml에서 완전 제거:
- `prometheus`
- `influxdb`
- `grafana`

k6는 로컬에서만 실행. EC2에 올리지 않는다.

제거 후 메모리 합산:

| 서비스 | 제한 |
|--------|------|
| Spring Boot | 400m (-Xmx256m) |
| MySQL | 300m |
| Redis | 128m |
| RabbitMQ | 200m |
| **합계** | **~1,028m** |

OS + Docker 오버헤드 ~150m 포함하면 한계치이나 허용 범위.

### 3. 배포 구조

```
면접관 브라우저
    ↓ HTTPS
Vercel (React SPA, ticketing-app.vercel.app)
    ↓ HTTPS API 호출 / SSE
Cloudflare Named Tunnel (도메인: ticket-rush.xyz 등)
    ↓ HTTP (터널 내부)
EC2 t2.micro
    └─ Docker Compose
        ├─ Spring Boot :8080
        ├─ MySQL
        ├─ Redis
        └─ RabbitMQ
```

**도메인:** Porkbun에서 `.xyz` 구입 → Cloudflare에 사이트 추가 → Porkbun nameserver를 Cloudflare nameserver로 교체 → Named Tunnel 연결.

**SSE 안정성:** Cloudflare 무료 터널 100초 타임아웃 → 30초 heartbeat ping으로 대응 (이미 구현됨).

**배포 순서:**
1. Porkbun에서 도메인 구입
2. Cloudflare 무료 계정에 도메인 추가
3. Porkbun DNS에서 Cloudflare nameserver로 교체, 전파 대기
4. EC2에 `cloudflared` 설치 + Named Tunnel 생성
5. `cloudflared service install` → systemd 등록 (EC2 재시작 시 자동 복구)
6. docker-compose.yaml 수정 (모니터링 제거) 후 `docker-compose up -d`
7. Vercel에 React 배포 + `VITE_API_URL=https://<도메인>` 환경변수 설정
8. Spring Boot WebConfig CORS에 Vercel 도메인 추가

## What We're NOT Doing

- 파라미터를 "부하 직전"으로 맞추는 것 — 부하 증명은 k6가 담당
- React 화면에 k6 배지/기술 스택 표시 — 체험용 UI 순수하게 유지, 숫자는 README에
- Cloudflare에서 직접 도메인 구입 — Porkbun .xyz로 비용 절감, nameserver 이전으로 동일 기능
- Phase C (스펙테이터 대시보드) — 배포 안정화 후 별도 단계

## Success Criteria

- 면접관이 URL 하나로 접속, 봇 150개와 경쟁
- 대기 중 SSE로 순번 실시간 감소 확인 (평균 12-15초)
- 예매 성공 또는 품절 화면 정상 노출
- t2.micro 30분 이상 OOM 없이 무중단
- `POST /api/admin/reset`으로 30초 내 데모 재시작
