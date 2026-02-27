import http from 'k6/http';
import { sleep, check } from 'k6';

export const options = {
    vus: 500,
    duration: '30s',
};

const BASE_URL = 'http://localhost:8080';

export default function () {
    const userId = __VU.toString(); // 가상 유저 ID를 문자열 헤더로 사용
    const concertId = 1;
    const seatId = userId;

    // 공통 헤더 설정
    const params = {
        headers: {
            'Content-Type': 'application/json',
            'X-USER-ID': userId, // 서버에서 요구하는 헤더 키값으로 변경하세요
        },
    };

// --- [STEP 1: SSE 구독 먼저 시작] ---
    // 구독 루프를 제어하기 위한 플래그
    let isWaiting = true;
    let hasGoSignal = false;
    let joinedQueue = false;

    // SSE 구독 요청 (비동기 시뮬레이션을 위해 루프 구성)
    while (isWaiting) {
        // 아직 대기열에 참여하지 않았다면, 구독 연결 시도와 거의 동시에 참여
        if (!joinedQueue) {
            // 1. 구독 시도 (비차단 혹은 짧은 타임아웃으로 우선 연결)
            // 주의: k6의 http.get은 동기식이므로, 서버가 연결을 즉시 수락한다고 가정합니다.
            const sseRes = http.get(`${BASE_URL}/api/subscribe`, {
                headers: params.headers,
                timeout: '1s', // 연결 확립 확인용 짧은 타임아웃
            });

            // 2. 구독 연결이 시작되면(혹은 직후에) 대기열 진입
            const joinRes = http.post(`${BASE_URL}/api/queue/join`, JSON.stringify({ concertId }), params);
            check(joinRes, { '1. 대기열 진입 성공': (r) => r.status === 200 });
            joinedQueue = true;

            // 만약 첫 구독에서 바로 신호가 왔는지 확인
            if (sseRes.body && sseRes.body.includes('GO_BOOKING')) {
                hasGoSignal = true;
                isWaiting = false;
                break;
            }
        } else {
            // 이미 진입한 상태라면 신호가 올 때까지 재연결하며 대기
            const sseRes = http.get(`${BASE_URL}/api/subscribe`, {
                headers: params.headers,
                timeout: '60s',
            });

            if (sseRes.status === 200 && sseRes.body && sseRes.body.includes('GO_BOOKING')) {
                hasGoSignal = true;
                isWaiting = false;
            } else {
                // 단순 타임아웃이나 순번 알림인 경우 잠시 쉬고 재연결
                sleep(1);
            }
        }
    }

    // --- [STEP 2: 예매 요청 및 폴링] ---
    if (hasGoSignal) {
        // 3. 예매 요청
        const bookingRes = http.post(`${BASE_URL}/api/v1/bookings`, JSON.stringify({
            concertId: concertId,
            seatId: seatId
        }), params);

        check(bookingRes, { '2. 예매 요청 접수(202)': (r) => r.status === 202 });

        // 4. 예매 결과 폴링
        let isProcessing = true;
        while (isProcessing) {
            const statusRes = http.get(`${BASE_URL}/api/bookings/status`, params);
            const statusBody = statusRes.json();

            if (statusBody.status === 'SUCCESS') {
                console.log(`[User ${userId}] 예매 최종 성공!`);
                isProcessing = false;
            } else if (statusBody.status === 'FAIL') {
                console.warn(`[User ${userId}] 예매 최종 실패: ${statusBody.message}`);
                isProcessing = false;
            } else {
                sleep(0.5); // 처리 중(WAITING)이면 재시도
            }
        }
    }
}