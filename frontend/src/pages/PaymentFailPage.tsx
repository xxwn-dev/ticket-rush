import { useEffect, useRef } from 'react'
import { useNavigate, useSearchParams } from 'react-router-dom'
import { post } from '../api/client'

export default function PaymentFailPage() {
  const [searchParams] = useSearchParams()
  const navigate = useNavigate()
  const message = searchParams.get('message') ?? '결제가 취소되었습니다.'
  const cancelledRef = useRef(false)

  useEffect(() => {
    if (cancelledRef.current) return
    cancelledRef.current = true

    const orderId = searchParams.get('orderId')
    if (orderId) {
      // 좌석 복원 + active_user 토큰 정리 (best-effort, 실패해도 UI는 정상 표시)
      post('/api/payments/cancel', { orderId }).catch(() => {})
    }
  }, [])

  return (
    <div className="min-h-screen bg-white">
      <header className="border-b border-gray-200 px-6 py-4">
        <h1 className="text-xl font-bold tracking-tight text-gray-900">TICKET RUSH</h1>
      </header>

      <div className="max-w-md mx-auto px-4 py-16 flex flex-col items-center text-center">
        <div className="text-5xl mb-4">❌</div>
        <h2 className="text-2xl font-bold text-gray-900 mb-2">결제 실패</h2>
        <p className="text-gray-500 mb-8">{message}</p>
        <button
          onClick={() => navigate('/')}
          className="w-full py-3 bg-blue-600 text-white font-bold rounded-xl hover:bg-blue-700 transition-colors"
        >
          처음으로 돌아가기
        </button>
      </div>
    </div>
  )
}
