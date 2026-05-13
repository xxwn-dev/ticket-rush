/**
 * V2 부하 테스트 — Lua Script 원자적 선점 방식
 *
 * 실행:
 *   k6 run --out influxdb=http://localhost:8086/k6 load-test/v2-comparison.js
 *
 * V1과 동일 조건(VU/duration/좌석 선택)으로 실행해 결과를 비교한다.
 * V2의 핵심 차이: Lua SREM으로 좌석 선점을 원자적으로 처리 → 409 거부가 즉각적
 * V1은 Redisson 락 획득 대기로 인해 hot seat 경쟁 시 지연이 누적된다.
 */

import http from 'k6/http';
import { check, sleep } from 'k6';

// ── 좌석 선택 ── v1-comparison.js와 동일한 로직
const HOT_SEATS = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10]; // 인기 좌석 — Lua SREM 원자성 검증

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
        'http_req_duration{endpoint:booking}': ['p(95)<3000'],
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
    const sseRes = http.get(`${BASE_URL}/api/subscribe`, {
        headers,
        timeout: '10s',
        tags: { endpoint: 'sse' },
    });

    if (!sseRes.body || !sseRes.body.includes('GO_BOOKING')) return;

    // ── STEP 3: V2 예매 요청 ──────────────────────────────────────────────
    // Lua Script(SREM)가 원자적으로 좌석을 선점
    // 응답: 202 Accepted (선점 성공, MQ 큐잉) | 409 Conflict (이미 선점됨 — 즉각 거부)
    // V1과 달리 DB 처리 결과를 기다리지 않고 Redis 레벨에서 즉시 결정
    const seatId = pickSeatId();
    const bookingRes = http.post(
        `${BASE_URL}/api/v2/bookings`,
        JSON.stringify({ concertId: CONCERT_ID, seatId }),
        { headers, tags: { endpoint: 'booking' } }
    );

    const bookingOk = bookingRes.status === 202 || bookingRes.status === 409;
    check(bookingRes, {
        '2. V2 예매 정상 처리(202/409)': () => bookingOk,
    });

    if (bookingRes.status !== 202) return;

    // ── STEP 4: 예매 결과 폴링 (최대 5회) ────────────────────────────────
    // MQ → BookingConsumer → DB 저장 완료 확인
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
