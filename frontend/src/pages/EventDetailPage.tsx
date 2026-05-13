import { useCallback, useEffect, useRef, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { loadTossPayments, ANONYMOUS } from '@tosspayments/tosspayments-sdk'
import { joinQueue, createBooking, fetchBookingStatus } from '../api/events'
import { useSSE } from '../hooks/useSSE'

type Step = 'queue' | 'booking' | 'result'
type ResultType = 'success' | 'fail' | 'expired'

const BOOKING_TTL_SECONDS = 600

export default function EventDetailPage() {
  const { id } = useParams<{ id: string }>()
  const navigate = useNavigate()
  const eventId = Number(id)

  const [step, setStep] = useState<Step>('queue')
  const [rank, setRank] = useState<number | null>(null)
  const [resultType, setResultType] = useState<ResultType>('success')
  const [seatNumber, setSeatNumber] = useState<string | null>(null)
  const [countdown, setCountdown] = useState(BOOKING_TTL_SECONDS)
  const [booking, setBooking] = useState(false)
  const countdownRef = useRef<ReturnType<typeof setInterval> | null>(null)

  // 대기열 진입
  useEffect(() => {
    joinQueue(eventId).then(({ data }) => {
      if (data) setRank(data.rank)
    })
  }, [eventId])

  // SSE: GO_BOOKING 수신 시 예매 단계로 전환
  const handleGoBooking = useCallback(() => {
    setStep('booking')
    countdownRef.current = setInterval(() => {
      setCountdown((prev) => {
        if (prev <= 1) {
          clearInterval(countdownRef.current!)
          setResultType('expired')
          setStep('result')
          return 0
        }
        return prev - 1
      })
    }, 1000)
  }, [])

  const handleRankUpdate = useCallback((r: number) => setRank(r), [])

  useSSE({ onGoBooking: handleGoBooking, onRankUpdate: handleRankUpdate }, step === 'queue')

  useEffect(() => {
    return () => {
      if (countdownRef.current) clearInterval(countdownRef.current)
    }
  }, [])

  const handleBook = async () => {
    setBooking(true)
    try {
      const { status, data } = await createBooking(eventId)
      clearInterval(countdownRef.current!)

      if (status === 200 && data) {
        // 좌석 선점 성공 → Toss 결제 위젯 실행
        const tossPayments = await loadTossPayments(import.meta.env.VITE_TOSS_CLIENT_KEY)
        const payment = tossPayments.payment({ customerKey: ANONYMOUS })
        await payment.requestPayment({
          method: 'CARD',
          amount: { currency: 'KRW', value: data.amount ?? 30000 },
          orderId: data.orderId,
          orderName: `LG vs KIA · 잠실 좌석 ${data.seatId}`,
          successUrl: `${window.location.origin}/payment/success`,
          failUrl: `${window.location.origin}/payment/fail`,
        })
        // requestPayment는 리다이렉트라 아래 코드는 실행되지 않음
      } else {
        setResultType('fail')
        setStep('result')
      }
    } catch {
      setResultType('fail')
      setStep('result')
    } finally {
      setBooking(false)
    }
  }

  const formatCountdown = (sec: number) => {
    const m = String(Math.floor(sec / 60)).padStart(2, '0')
    const s = String(sec % 60).padStart(2, '0')
    return `${m}:${s}`
  }

  return (
    <div className="min-h-screen bg-white">
      {/* 헤더 */}
      <header className="border-b border-gray-200 px-6 py-4 flex items-center gap-4">
        <button onClick={() => navigate('/')} className="text-gray-500 hover:text-gray-700 text-sm">
          ← 목록
        </button>
        <h1 className="text-xl font-bold tracking-tight text-gray-900">TICKET RUSH</h1>
      </header>

      {/* 이벤트 서브헤더 */}
      <div className="bg-gray-50 border-b border-gray-100 px-6 py-3">
        <p className="text-sm text-gray-600">LG vs KIA · 잠실 &middot; 2026.06.01 (월) 18:00</p>
      </div>

      <div className="max-w-md mx-auto px-4 py-16 flex flex-col items-center text-center">

        {/* Step 1: 대기열 */}
        {step === 'queue' && (
          <>
            <p className="text-gray-500 text-sm mb-6">대기열에 입장했습니다</p>
            <div className="text-7xl font-bold text-gray-900 mb-2">
              {rank !== null ? rank.toLocaleString() : '—'}
            </div>
            <p className="text-gray-500 text-sm mb-8">내 앞 대기 인원</p>
            <div className="w-full bg-gray-100 rounded-full h-2 mb-4">
              <div className="bg-blue-500 h-2 rounded-full animate-pulse w-1/3" />
            </div>
            <p className="text-gray-400 text-sm">잠시만 기다려주세요...</p>
          </>
        )}

        {/* Step 2: 예매 */}
        {step === 'booking' && (
          <>
            <div className="text-4xl mb-4">🎉</div>
            <h2 className="text-2xl font-bold text-gray-900 mb-2">예매 차례가 되었습니다!</h2>
            <p className="text-gray-500 text-sm mb-8">랜덤 좌석이 자동으로 배정됩니다</p>

            <div className="text-5xl font-mono font-bold text-blue-600 mb-8">
              {formatCountdown(countdown)}
            </div>

            <button
              onClick={handleBook}
              disabled={booking}
              className="w-full py-4 bg-blue-600 text-white font-bold text-lg rounded-xl hover:bg-blue-700 disabled:opacity-50 transition-colors"
            >
              {booking ? '처리 중...' : '지금 예매하기'}
            </button>
          </>
        )}

        {/* Step 3: 결과 */}
        {step === 'result' && (
          <>
            {resultType === 'success' ? (
              <>
                <div className="text-5xl mb-4">✅</div>
                <h2 className="text-2xl font-bold text-gray-900 mb-2">예매 완료!</h2>
                <p className="text-gray-500 mb-1">LG vs KIA · 잠실</p>
                <p className="text-gray-500 mb-1">2026.06.01 (월) 18:00</p>
                {seatNumber && (
                  <p className="text-blue-600 font-semibold mt-2">{seatNumber}</p>
                )}
                <button
                  onClick={() => navigate('/')}
                  className="mt-10 w-full py-3 border border-gray-300 rounded-xl text-gray-700 font-medium hover:bg-gray-50 transition-colors"
                >
                  처음으로 돌아가기
                </button>
              </>
            ) : (
              <>
                <div className="text-5xl mb-4">❌</div>
                <h2 className="text-2xl font-bold text-gray-900 mb-2">
                  {resultType === 'expired' ? '시간이 초과되었습니다' : '예매에 실패했습니다'}
                </h2>
                <p className="text-gray-500 mb-8">
                  {resultType === 'expired'
                    ? '예매 가능 시간이 지났습니다.'
                    : '좌석이 모두 소진되었습니다.'}
                </p>
                <button
                  onClick={() => navigate('/')}
                  className="w-full py-3 bg-blue-600 text-white font-bold rounded-xl hover:bg-blue-700 transition-colors"
                >
                  다시 시도하기
                </button>
              </>
            )}
          </>
        )}
      </div>
    </div>
  )
}
