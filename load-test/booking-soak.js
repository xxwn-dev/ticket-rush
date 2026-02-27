import http from 'k6/http';
import { check, sleep } from 'k6';

export const options = {
    vus: 100,
    duration: '3m',
};

const BASE_URL = 'http://localhost:8080';

export default function () {
    const userId = __VU;
    const concertId = 1;

    const jsonParams = {
        headers: {
            'Content-Type': 'application/json',
            'X-USER-ID': userId.toString(),
        },
    };

    const sseParams = {
        headers: {
            'X-USER-ID': userId.toString(),
            'Accept': 'text/event-stream',
            'Cache-Control': 'no-cache',
        },
        timeout: '60s',
    };

    // 1. 대기열 진입
    const joinRes = http.post(`${BASE_URL}/api/queue/join`, JSON.stringify({ concertId }), jsonParams);
    if (joinRes.status !== 200) return;

    // 2. SSE 구독 및 입장 대기
    const sseRes = http.get(`${BASE_URL}/api/subscribe`, sseParams);

    // 3. 진입 허가(GO_BOOKING) 확인 시 예매 진행
    if (sseRes.status === 200 && (sseRes.body.includes('GO_BOOKING') || sseRes.body.includes('queue'))) {

        const seatId = userId; // 유저 번호를 좌석 번호로 활용
        const bookingPayload = JSON.stringify({ concertId, seatId });

        // [POST] 예매 요청
        const bookingRes = http.post(`${BASE_URL}/api/v2/bookings`, bookingPayload, jsonParams);

        if (check(bookingRes, { '3. V2 예매 수락(202)': (r) => r.status === 202 })) {

            // 4. 최종 처리 상태 폴링
            let isConfirmed = false;
            for (let i = 0; i < 10; i++) {
                const statusRes = http.get(`${BASE_URL}/api/status`, jsonParams);
                if (statusRes.status === 200 && statusRes.body.includes('SUCCESS')) {
                    isConfirmed = true;
                    break;
                }
                sleep(1);
            }

            // 5. [DELETE] 예매 취소 호출
            if (isConfirmed) {
                // DELETE 메서드도 동일한 URL(/api/v2/bookings)을 사용합니다.
                const cancelRes = http.del(`${BASE_URL}/api/v2/bookings`, bookingPayload, jsonParams);

                check(cancelRes, {
                    '4. 예매 취소 성공(202/200)': (r) => r.status === 202 || r.status === 200,
                });
            }
        }
    }

    sleep(1);
}