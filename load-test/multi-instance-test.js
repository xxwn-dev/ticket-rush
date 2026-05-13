/**
 * 멀티 인스턴스 검증 테스트
 *
 * 목적: nginx(round-robin) → app1/app2 분산 환경에서
 *       Redis Pub/Sub 기반 SSE 크로스 인스턴스 전달이 정상 동작하는지 검증
 *
 * 핵심 시나리오:
 *   - queue join 요청이 app1으로 라우팅되더라도
 *     SSE 구독이 app2에 연결된 경우, app2의 QueuePromotionListener가
 *     Redis PUBLISH 메시지를 수신해 GO_BOOKING을 전달해야 함
 *
 * 실행:
 *   docker-compose --profile full up -d
 *   k6 run --out influxdb=http://localhost:8086/k6 load-test/multi-instance-test.js
 */
import http from 'k6/http';
import { check, sleep } from 'k6';

const TOTAL_SEATS = 200;
const HOT_SEATS = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10];

function pickSeatId() {
    if (Math.random() < 0.6) {
        return HOT_SEATS[Math.floor(Math.random() * HOT_SEATS.length)];
    }
    return Math.floor(Math.random() * TOTAL_SEATS) + 1;
}

export const options = {
    stages: [
        { duration: '30s', target: 100 },
        { duration: '1m',  target: 300 },  // 피크 (300 VU)
        { duration: '1m',  target: 300 },  // 유지
        { duration: '30s', target: 0 },
    ],
    thresholds: {
        'http_req_duration{type:booking}':   ['p(95)<3000'],
        'http_req_duration{type:queue}':     ['p(95)<200'],
        'http_req_duration{type:sse}':       ['p(95)<3000'],
        // 크로스 인스턴스 SSE 전달 성공률 — 80% 이상이면 Pub/Sub 정상
        'checks{check:SSE GO_BOOKING 수신}': ['rate>0.8'],
    },
};

// nginx 경유 — round-robin으로 app1/app2에 분산됨
const BASE_URL = 'http://localhost:80';

export default function () {
    const userId = __VU;
    const concertId = 1;

    const baseParams = {
        headers: {
            'Content-Type': 'application/json',
            'X-USER-ID': userId.toString(),
        },
        timeout: '30s',
    };

    // ── STEP 1: 대기열 진입 (nginx → app1 or app2 중 하나로 라우팅) ─────────
    const joinRes = http.post(
        `${BASE_URL}/api/queue/join`,
        JSON.stringify({ concertId }),
        Object.assign({}, baseParams, { tags: { type: 'queue' } })
    );
    if (!check(joinRes, { '1. 대기열 진입 성공': (r) => r.status === 200 })) return;

    // ── STEP 2: SSE 구독 (nginx → 다른 인스턴스로 라우팅될 수 있음) ─────────
    // round-robin 특성상 ~50% 확률로 queue join과 다른 인스턴스에 연결됨
    // → 해당 인스턴스의 QueuePromotionListener가 Redis Pub/Sub 메시지 수신 후
    //   자신의 SSE 맵에서 userId를 찾아 GO_BOOKING 전송해야 함
    const sseRes = http.get(
        `${BASE_URL}/api/subscribe`,
        Object.assign({}, baseParams, {
            timeout: '10s',
            tags: { type: 'sse' },
        })
    );

    const hasAuth = sseRes.body && sseRes.body.includes('GO_BOOKING');
    check(sseRes, {
        'SSE GO_BOOKING 수신': () => sseRes.status === 200 && hasAuth,
    });

    if (!hasAuth) return;

    // ── STEP 3: 예매 요청 ──────────────────────────────────────────────────
    const seatId = pickSeatId();
    const bookingRes = http.post(
        `${BASE_URL}/api/v2/bookings`,
        JSON.stringify({ concertId, seatId }),
        Object.assign({}, baseParams, { tags: { type: 'booking' } })
    );

    const bookingOk = bookingRes.status === 202 || bookingRes.status === 409;
    check(bookingRes, {
        '3. 예매 정상 처리(202/409)': () => bookingOk,
    });

    if (!bookingOk) return;

    // ── STEP 4: 예매 결과 폴링 ────────────────────────────────────────────
    for (let i = 0; i < 3; i++) {
        sleep(1);
        const statusRes = http.get(
            `${BASE_URL}/api/status`,
            Object.assign({}, baseParams, { tags: { type: 'booking' } })
        );
        if (statusRes.status === 200) {
            const body = statusRes.json();
            if (body.status === 'CONFIRMED' || body.status === 'SUCCESS' || body.status === 'REJECTED') break;
        }
    }
}
