/**
 * V1 부하 테스트 — Redisson 분산 락 방식
 *
 * 실행:
 *   k6 run --out influxdb=http://localhost:8086/k6 load-test/v1-comparison.js
 *
 * V2와 동일 조건(VU/duration/좌석 선택)으로 실행해 결과를 비교한다.
 * Grafana에서 두 테스트 결과를 시간대별로 확인하거나,
 * 각 실행의 http_req_duration / checks 지표를 나란히 비교한다.
 */

import http from 'k6/http';
import { check, sleep } from 'k6';

// ── 좌석 선택 ── v2-comparison.js와 동일한 로직
const HOT_SEATS = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10]; // 인기 좌석 — Redisson 락 경쟁 유발

function pickSeatId() {
    if (Math.random() < 0.6) {
        return HOT_SEATS[Math.floor(Math.random() * HOT_SEATS.length)];
    }
    return Math.floor(Math.random() * 2000) + 1;
}

export const options = {
    vus: 200,
    iterations: 200, // VU당 1회 — 200명이 동시에 한 번씩 경쟁
    thresholds: {
        'http_req_duration{endpoint:booking}': ['p(95)<5000'],
        'http_req_duration{endpoint:queue}':   ['p(95)<500'],
        'checks':                              ['rate>0.4'],
    },
};

const BASE_URL = 'http://localhost:8080';
const CONCERT_ID = 1;

export default function () {
    const userId = __VU;
    const headers = {
        'Content-Type': 'application/json',
        'X-USER-ID': userId.toString(),
    };

    // ── STEP 1: 대기열 진입 ────────────────────────────────────────────────
    const joinRes = http.post(
        `${BASE_URL}/api/queue/join`,
        JSON.stringify({ concertId: CONCERT_ID }),
        { headers, tags: { endpoint: 'queue' } }
    );
    if (!check(joinRes, { '1. 대기열 진입(200)': (r) => r.status === 200 })) return;

    // ── STEP 2: SSE 구독 — GO_BOOKING 수신 대기 ───────────────────────────
    // 스케줄러(500ms) + 네트워크 여유를 고려해 10s timeout
    const sseRes = http.get(`${BASE_URL}/api/subscribe`, {
        headers,
        timeout: '10s',
        tags: { endpoint: 'sse' },
    });

    if (!sseRes.body || !sseRes.body.includes('GO_BOOKING')) return;

    // ── STEP 3: V1 예매 요청 ──────────────────────────────────────────────
    // 응답: 202 Accepted (MQ 큐잉 완료) | 409 Conflict (Redis 캐시 선점 실패)
    // 실제 DB 처리는 BookingConsumer → RedisLockFacade(Redisson 락) → DB 순으로 비동기 수행
    const seatId = pickSeatId();
    const bookingRes = http.post(
        `${BASE_URL}/api/v1/bookings`,
        JSON.stringify({ concertId: CONCERT_ID, seatId }),
        { headers, tags: { endpoint: 'booking' } }
    );

    const bookingOk = bookingRes.status === 202 || bookingRes.status === 409;
    check(bookingRes, {
        '2. V1 예매 정상 처리(202/409)': () => bookingOk,
    });

    if (bookingRes.status !== 202) return;

    // ── STEP 4: 예매 결과 폴링 (최대 5회) ────────────────────────────────
    // MQ 비동기 처리 완료를 확인 — Redisson 락 경합이 길수록 지연 발생
    for (let i = 0; i < 5; i++) {
        sleep(1);
        const statusRes = http.get(
            `${BASE_URL}/api/status`,
            { headers, tags: { endpoint: 'status' } }
        );
        if (statusRes.status === 200) {
            const body = statusRes.json();
            check(statusRes, {
                '3. 예매 최종 성공(SUCCESS)': () => body.status === 'SUCCESS',
            });
            if (body.status === 'SUCCESS' || body.status === 'FAIL') break;
        }
    }
}
