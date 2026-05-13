import { useEffect, useRef, useState } from 'react'
import { useNavigate, useSearchParams } from 'react-router-dom'
import { confirmPayment } from '../api/events'

export default function PaymentSuccessPage() {
  const [searchParams] = useSearchParams()
  const navigate = useNavigate()
  const [status, setStatus] = useState<'loading' | 'success' | 'fail'>('loading')
  const [seatId, setSeatId] = useState<number | null>(null)
  const confirmedRef = useRef(false)

  useEffect(() => {
    if (confirmedRef.current) return
    confirmedRef.current = true

    const paymentKey = searchParams.get('paymentKey')
    const orderId = searchParams.get('orderId')
    const amount = Number(searchParams.get('amount'))

    if (!paymentKey || !orderId || !amount) {
      setStatus('fail')
      return
    }

    confirmPayment(paymentKey, orderId, amount)
      .then(({ status, data }) => {
        if (status === 200 && data) {
          setSeatId(data.seatId)
          setStatus('success')
        } else {
          setStatus('fail')
        }
      })
      .catch(() => setStatus('fail'))
  }, [])

  return (
    <div className="min-h-screen bg-white">
      <header className="border-b border-gray-200 px-6 py-4">
        <h1 className="text-xl font-bold tracking-tight text-gray-900">TICKET RUSH</h1>
      </header>

      <div className="max-w-md mx-auto px-4 py-16 flex flex-col items-center text-center">
        {status === 'loading' && (
          <>
            <div className="text-5xl mb-4 animate-pulse">⏳</div>
            <h2 className="text-2xl font-bold text-gray-900">결제 확인 중...</h2>
          </>
        )}

        {status === 'success' && (
          <>
            <div className="text-5xl mb-4">✅</div>
            <h2 className="text-2xl font-bold text-gray-900 mb-2">예매 완료!</h2>
            <p className="text-gray-500 mb-1">LG vs KIA · 잠실</p>
            <p className="text-gray-500 mb-1">2026.06.01 (월) 18:00</p>
            {seatId && (
              <p className="text-blue-600 font-semibold mt-2">좌석 {seatId}</p>
            )}
            <button
              onClick={() => navigate('/')}
              className="mt-10 w-full py-3 border border-gray-300 rounded-xl text-gray-700 font-medium hover:bg-gray-50 transition-colors"
            >
              처음으로 돌아가기
            </button>
          </>
        )}

        {status === 'fail' && (
          <>
            <div className="text-5xl mb-4">❌</div>
            <h2 className="text-2xl font-bold text-gray-900 mb-2">결제 확인 실패</h2>
            <p className="text-gray-500 mb-8">다시 시도해주세요.</p>
            <button
              onClick={() => navigate('/')}
              className="w-full py-3 bg-blue-600 text-white font-bold rounded-xl hover:bg-blue-700 transition-colors"
            >
              처음으로 돌아가기
            </button>
          </>
        )}
      </div>
    </div>
  )
}
