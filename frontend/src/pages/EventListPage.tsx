import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { fetchEvents, triggerBots, type Event } from '../api/events'

function SkeletonRow() {
  return (
    <div className="flex items-center justify-between py-5 border-b border-gray-100 animate-pulse">
      <div className="flex items-center gap-6">
        <div className="w-10 text-center">
          <div className="h-4 w-6 bg-gray-200 rounded mb-1" />
          <div className="h-3 w-6 bg-gray-200 rounded" />
        </div>
        <div>
          <div className="h-5 w-48 bg-gray-200 rounded mb-2" />
          <div className="h-3 w-32 bg-gray-200 rounded" />
        </div>
      </div>
      <div className="h-9 w-28 bg-gray-200 rounded-full" />
    </div>
  )
}

export default function EventListPage() {
  const navigate = useNavigate()
  const [events, setEvents] = useState<Event[]>([])
  const [loading, setLoading] = useState(true)
  const [botLoading, setBotLoading] = useState(false)

  useEffect(() => {
    fetchEvents()
      .then(setEvents)
      .finally(() => setLoading(false))
  }, [])

  const handleTriggerBots = async (eventId: number) => {
    setBotLoading(true)
    await triggerBots(eventId).catch(() => null)
    setBotLoading(false)
    // 잔여석 갱신
    fetchEvents().then(setEvents)
  }

  const formatDate = (iso: string) => {
    const d = new Date(iso)
    return {
      month: d.getMonth() + 1,
      day: d.getDate(),
      weekday: ['일', '월', '화', '수', '목', '금', '토'][d.getDay()],
      time: `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`,
    }
  }

  return (
    <div className="min-h-screen bg-white">
      {/* 헤더 */}
      <header className="border-b border-gray-200 px-6 py-4 flex items-center justify-between">
        <h1 className="text-xl font-bold tracking-tight text-gray-900">TICKET RUSH</h1>
      </header>

      {/* 히어로 */}
      <div className="relative h-52 bg-gray-800 overflow-hidden">
        <div className="absolute inset-0 bg-gradient-to-r from-gray-900/80 to-gray-900/40" />
        <div className="absolute inset-0 flex flex-col justify-end p-6">
          <p className="text-gray-400 text-xs uppercase tracking-widest mb-1">MLB BASEBALL</p>
          <h2 className="text-white text-3xl font-bold">LG vs KIA · 잠실</h2>
        </div>
      </div>

      {/* 이벤트 목록 */}
      <div className="max-w-3xl mx-auto px-4 py-6">
        <div className="flex items-center justify-between mb-4">
          <h3 className="text-lg font-semibold text-gray-900">전체 경기</h3>
        </div>

        {loading ? (
          <>
            <SkeletonRow />
            <SkeletonRow />
            <SkeletonRow />
          </>
        ) : events.length === 0 ? (
          <p className="text-gray-500 py-10 text-center">등록된 이벤트가 없습니다.</p>
        ) : (
          events.map((event) => {
            const { month, day, weekday, time } = formatDate(event.openAt)
            const soldOut = event.status === 'SOLD_OUT' || event.availableSeats === 0

            return (
              <div key={event.id} className="flex items-center justify-between py-5 border-b border-gray-100">
                {/* 날짜 */}
                <div className="flex items-center gap-6">
                  <div className="w-10 text-center">
                    <p className="text-sm font-semibold text-gray-900">{month}/{day}</p>
                    <p className="text-xs text-gray-500">({weekday})</p>
                  </div>

                  {/* 이벤트 정보 */}
                  <div>
                    <p className="font-semibold text-gray-900">{event.title}</p>
                    <p className="text-sm text-gray-500 mt-0.5">
                      {time} &middot;&nbsp;
                      {soldOut ? (
                        <span className="text-red-500 font-medium">매진</span>
                      ) : (
                        <span className="text-green-600">{event.availableSeats.toLocaleString()}석 남음</span>
                      )}
                    </p>
                  </div>
                </div>

                {/* 버튼 영역 */}
                <div className="flex items-center gap-2">
                  <button
                    onClick={() => handleTriggerBots(event.id)}
                    disabled={botLoading}
                    className="text-xs px-3 py-1.5 border border-gray-300 rounded-full text-gray-600 hover:bg-gray-50 disabled:opacity-40 transition-colors"
                  >
                    {botLoading ? '실행 중...' : '봇 실행'}
                  </button>
                  <button
                    onClick={() => navigate(`/events/${event.id}`)}
                    disabled={soldOut}
                    className="px-5 py-2 rounded-full text-sm font-semibold transition-colors
                      bg-blue-600 text-white hover:bg-blue-700
                      disabled:bg-gray-200 disabled:text-gray-400 disabled:cursor-not-allowed"
                  >
                    {soldOut ? '매진' : '예매하기 →'}
                  </button>
                </div>
              </div>
            )
          })
        )}
      </div>
    </div>
  )
}
