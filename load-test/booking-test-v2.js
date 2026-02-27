import http from 'k6/http';
import { check, sleep } from 'k6';

export const options = {
    vus: 100,
    //duration: '30s',
    iterations:100,
};

const BASE_URL = 'http://localhost:8080';

export default function () {
    const userId = __VU; // __VU는 숫자입니다.
    const concertId = 1;
    const params = {
        headers: {
            'Content-Type': 'application/json',
            'X-USER-ID': userId.toString(),
        },
    };

    // --- [STEP 1: 대기열 진입] ---
    const joinRes = http.post(`${BASE_URL}/api/queue/join`, JSON.stringify({ concertId }), params);
    if (joinRes.status !== 200) return;

    // --- [STEP 2: SSE 구독 및 입장 대기] ---
    const sseRes = http.get(`${BASE_URL}/api/subscribe`, {
        headers: params.headers,
        timeout: '10s',
    });

    // --- [STEP 3: 비동기 예매 요청 (V2)] ---
    if (sseRes.status === 200 && sseRes.body.includes('GO_BOOKING')) {

        // userId가 Long 타입인 seatId 역할을 수행 (예: 1~100번 좌석)
        const seatId = userId;

        const bookingRes = http.post(`${BASE_URL}/api/v2/bookings`, JSON.stringify({
            concertId: concertId,
            seatId: seatId // 이제 Long 타입(숫자)으로 전달됩니다.
        }), params);

        check(bookingRes, {
            '3. V2 예매 요청 수락(202)': (r) => r.status === 202,
        });

        sleep(1);

        if(userId <= 50) {
            const delRes = http.del(`${BASE_URL}/api/v2/bookings`, JSON.stringify({
                concertId: concertId,
                seatId: seatId
            }), params);

            check(delRes, {
                '취소 요청 성공(202/200)' : (r) => r.status === 202 || r.status === 200,
            });
        }
        // --- [STEP 4: 최종 처리 상태 폴링] ---
        let isProcessing = true;
        let retryCount = 0;
        while (isProcessing && retryCount < 5) {
            const statusRes = http.get(`${BASE_URL}/api/status`, params);
            if (statusRes.status === 200 && statusRes.json().status === 'SUCCESS') {
                isProcessing = false;
            } else {
                retryCount++;
                sleep(1);
            }
        }
    }
}