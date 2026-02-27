import http from 'k6/http';
import { check, sleep } from 'k6';

// ── 좌석 설정 ──────────────────────────────────────────────────────────────
// DataInitializer가 생성하는 2000석 중 인기 좌석 집중 분포 시뮬레이션
// 실제 티케팅: 앞 좌석/특정 구역에 트래픽 집중 → hot-seat contention 재현
const TOTAL_SEATS = 2000;
const HOT_SEATS = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10]; // 인기 좌석 10개

function pickSeatId() {
    // 60%는 인기 좌석 집중 (동시성 제어 로직 압박)
    // 40%는 전체 좌석 랜덤 (매진 시나리오 포함)
    if (Math.random() < 0.6) {
        return HOT_SEATS[Math.floor(Math.random() * HOT_SEATS.length)];
    }
    return Math.floor(Math.random() * TOTAL_SEATS) + 1;
}

export const options = {
    stages: [
        { duration: '30s', target: 100 },
        { duration: '1m',  target: 500 },
        { duration: '1m',  target: 1000 },
        { duration: '1m',  target: 2000 }, // 피크 유지
        { duration: '30s', target: 0 },
    ],
    thresholds: {
        // booking 202/409는 정상 비즈니스 응답 — 인프라 오류(5xx, status 0)만 측정
        'http_req_failed{type:infra}': ['rate<0.05'],
        'http_req_duration{type:booking}': ['p(95)<3000'],
        'http_req_duration{type:queue}':   ['p(95)<200'],  // queue join만
        'http_req_duration{type:sse}':     ['p(95)<3000'], // SSE 대기
    },
};

const BASE_URL = 'http://localhost:8080';

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

    // ── STEP 1: 대기열 진입 ────────────────────────────────────────────────
    const joinRes = http.post(
        `${BASE_URL}/api/queue/join`,
        JSON.stringify({ concertId }),
        Object.assign({}, baseParams, { tags: { type: 'queue' } })
    );
    if (!check(joinRes, { '1. 대기열 진입 성공': (r) => r.status === 200 })) return;

    // ── STEP 2: SSE 구독 ───────────────────────────────────────────────────
    // 서버는 GO_BOOKING 전송 후 emitter.complete()로 연결을 닫음
    // → k6가 응답 수신 후 정상 종료 (status 200)
    // 스케줄러(500ms) + 네트워크 여유 고려해 10s timeout
    const sseRes = http.get(
        `${BASE_URL}/api/subscribe`,
        Object.assign({}, baseParams, {
            timeout: '10s',
            tags: { type: 'sse' },
        })
    );

    const hasAuth = sseRes.body && sseRes.body.includes('GO_BOOKING');
    check(sseRes, {
        // status 0: SSE timeout — 대기열이 아직 미통과, 예매 건너뜀 (정상)
        '2. SSE GO_BOOKING 수신': (r) => r.status === 200 && hasAuth,
    });

    if (!hasAuth) return;

    // ── STEP 3: 예매 요청 (좌석 경쟁 시나리오) ─────────────────────────────
    // seatId를 VU 고정이 아닌 랜덤으로 선택해 실제 동시성 제어 로직을 압박
    const seatId = pickSeatId();

    const bookingRes = http.post(
        `${BASE_URL}/api/v2/bookings`,
        JSON.stringify({ concertId, seatId }),
        Object.assign({}, baseParams, { tags: { type: 'booking' } })
    );

    // 202: 예매 수락 / 409: 이미 예매된 좌석 (정상 비즈니스 응답)
    // 403: 대기열 미통과 (SSE timeout 후 진행한 경우, 비정상이므로 실패 처리)
    const bookingOk = bookingRes.status === 202 || bookingRes.status === 409;
    check(bookingRes, {
        '3. 예매 요청 정상 처리(202/409)': () => bookingOk,
    });

    // 인프라 오류(5xx, status 0) 만 별도 태그로 추적
    if (bookingRes.status >= 500 || bookingRes.status === 0) {
        bookingRes.tag({ type: 'infra' });
    }

    if (!bookingOk) return;

    // ── STEP 4: 10% 확률로 취소 → 재고 복구 검증 ────────────────────────
    if (bookingRes.status === 202 && Math.random() < 0.1) {
        const delRes = http.del(
            `${BASE_URL}/api/v2/bookings`,
            JSON.stringify({ concertId, seatId }),
            Object.assign({}, baseParams, { tags: { type: 'booking' } })
        );
        check(delRes, {
            '4. 취소 및 재고 복구(202/200)': (r) => r.status === 202 || r.status === 200,
        });
    }

    // ── STEP 5: 예매 결과 폴링 (최대 3회) ────────────────────────────────
    for (let i = 0; i < 3; i++) {
        sleep(1);
        const statusRes = http.get(
            `${BASE_URL}/api/status`,
            Object.assign({}, baseParams, { tags: { type: 'booking' } })
        );
        if (statusRes.status === 200) {
            const body = statusRes.json();
            if (body.status === 'CONFIRMED' || body.status === 'REJECTED') break;
        }
    }
}
