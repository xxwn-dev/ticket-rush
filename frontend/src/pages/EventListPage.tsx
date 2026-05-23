import { useEffect, useState } from 'react'
import { useNavigate, useSearchParams } from 'react-router-dom'
import { fetchEvents, triggerBots, type Event } from '../api/events'

const TEAM_COLORS: Record<string, string> = {
  'LG 트윈스': '#C30452',
  'KIA 타이거즈': '#EA0029',
  '두산 베어스': '#131230',
  'SSG 랜더스': '#CE0E2D',
  '롯데 자이언츠': '#041E42',
  '삼성 라이온즈': '#074CA1',
  'NC 다이노스': '#315288',
  '한화 이글스': '#FF6600',
  'KT 위즈': '#000000',
  '키움 히어로즈': '#820024',
}

const TEAMS = Object.keys(TEAM_COLORS)

function getThisWeekTuesday(): Date {
  const today = new Date()
  today.setHours(0, 0, 0, 0)
  const dayOfWeek = today.getDay() // 0=Sun,1=Mon,2=Tue,...
  const daysBack = (dayOfWeek - 2 + 7) % 7
  const tuesday = new Date(today)
  tuesday.setDate(today.getDate() - daysBack)
  return tuesday
}

function addDays(date: Date, days: number): Date {
  const d = new Date(date)
  d.setDate(d.getDate() + days)
  return d
}

function formatWeekRange(start: Date): string {
  const end = addDays(start, 5) // Tue + 5 = Sun
  const fmt = (d: Date) => `${d.getMonth() + 1}/${d.getDate()}`
  const weekdays = ['일', '월', '화', '수', '목', '금', '토']
  return `${fmt(start)}(${weekdays[start.getDay()]}) ~ ${fmt(end)}(${weekdays[end.getDay()]})`
}

function isInWeek(startTimeStr: string, weekStart: Date): boolean {
  const d = new Date(startTimeStr)
  d.setHours(0, 0, 0, 0)
  const weekEnd = addDays(weekStart, 6)
  return d >= weekStart && d <= weekEnd
}

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
  const [searchParams, setSearchParams] = useSearchParams()
  const [allEvents, setAllEvents] = useState<Event[]>([])
  const [loading, setLoading] = useState(true)
  const [botLoading, setBotLoading] = useState(false)

  const selectedTeam = searchParams.get('team')
  const gameFilter = (searchParams.get('filter') ?? 'all') as 'home' | 'all'
  const currentWeekStart = (() => {
    const w = searchParams.get('week')
    if (w) { const d = new Date(w); d.setHours(0,0,0,0); return d }
    return getThisWeekTuesday()
  })()

  const setSelectedTeam = (team: string | null) =>
    setSearchParams(p => { team ? p.set('team', team) : p.delete('team'); return p }, { replace: true })

  const setGameFilter = (f: 'home' | 'all') =>
    setSearchParams(p => { p.set('filter', f); return p }, { replace: true })

  const setCurrentWeekStart = (fn: (prev: Date) => Date) => {
    const next = fn(currentWeekStart)
    setSearchParams(p => { p.set('week', next.toISOString().slice(0, 10)); return p }, { replace: true })
  }

  useEffect(() => {
    fetchEvents()
      .then(setAllEvents)
      .finally(() => setLoading(false))
  }, [])

  const handleTriggerBots = async (eventId: number) => {
    setBotLoading(true)
    await triggerBots(eventId).catch(() => null)
    fetchEvents().then(setAllEvents)
    setBotLoading(false)
  }

  const filteredEvents = allEvents.filter(event => {
    if (!isInWeek(event.openAt, currentWeekStart)) return false
    if (selectedTeam) {
      if (gameFilter === 'home') return event.homeTeam === selectedTeam
      return event.homeTeam === selectedTeam || event.awayTeam === selectedTeam
    }
    return true
  })

  const heroColor = selectedTeam ? TEAM_COLORS[selectedTeam] : '#1e293b'

  const formatDate = (iso: string) => {
    const d = new Date(iso)
    return {
      month: d.getMonth() + 1,
      day: d.getDate(),
      weekday: ['일', '월', '화', '수', '목', '금', '토'][d.getDay()],
      time: `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`,
    }
  }

  const formatTicketOpen = (iso: string) => {
    const d = new Date(iso)
    return `${d.getMonth() + 1}/${d.getDate()} 오픈 예정`
  }

  return (
    <div className="min-h-screen bg-white">
      {/* 헤더 */}
      <header className="border-b border-gray-200 px-6 py-4 flex items-center justify-between">
        <h1 className="text-xl font-bold tracking-tight text-gray-900">TICKET RUSH</h1>
        <div className="flex items-center gap-4">
          <select
            value={selectedTeam ?? ''}
            onChange={e => setSelectedTeam(e.target.value || null)}
            className="text-sm border border-gray-300 rounded-lg px-3 py-1.5 bg-white"
          >
            <option value="">전체 팀</option>
            {TEAMS.map(t => <option key={t} value={t}>{t}</option>)}
          </select>
          <a href="/admin" className="text-xs text-gray-400 hover:text-gray-600">관리자</a>
        </div>
      </header>

      {/* 히어로 */}
      <div
        className="relative h-40 overflow-hidden transition-colors duration-300"
        style={{ backgroundColor: heroColor }}
      >
        <div className="absolute inset-0 bg-black/30 flex flex-col justify-end p-6">
          {selectedTeam ? (
            <h2 className="text-white text-3xl font-bold">{selectedTeam}</h2>
          ) : (
            <h2 className="text-white text-3xl font-bold">KBO 리그</h2>
          )}
        </div>
      </div>

      {/* 주 네비게이터 + 홈/원정 필터 */}
      <div className="max-w-3xl mx-auto px-4 pt-4 pb-2 relative flex items-center justify-center">
        <div className="flex items-center gap-3">
          <span className="text-sm font-medium text-gray-700">{formatWeekRange(currentWeekStart)}</span>
          <button
            onClick={() => setCurrentWeekStart(prev => addDays(prev, 7))}
            className="text-xs px-3 py-1.5 border border-gray-300 rounded-full hover:bg-gray-50"
          >
            다음 주 ▶
          </button>
        </div>
        {selectedTeam && (
          <div className="absolute right-4 flex items-center gap-3 text-sm">
            <label className="flex items-center gap-1.5 cursor-pointer">
              <input type="radio" checked={gameFilter === 'all'} onChange={() => setGameFilter('all')} />
              홈+원정
            </label>
            <label className="flex items-center gap-1.5 cursor-pointer">
              <input type="radio" checked={gameFilter === 'home'} onChange={() => setGameFilter('home')} />
              홈경기만
            </label>
          </div>
        )}
      </div>

      {/* 이벤트 목록 */}
      <div className="max-w-3xl mx-auto px-4 py-4">
        {loading ? (
          <>
            <SkeletonRow /><SkeletonRow /><SkeletonRow />
          </>
        ) : filteredEvents.length === 0 ? (
          <p className="text-gray-500 py-12 text-center">이번 주 경기가 없습니다.</p>
        ) : (
          filteredEvents.map(event => {
            const { month, day, weekday, time } = formatDate(event.openAt)
            const soldOut = event.status === 'SOLD_OUT' || event.availableSeats === 0
            const notOpen = !event.isTicketOpen

            return (
              <div key={event.id} className="flex items-center justify-between py-5 border-b border-gray-100">
                <div className="flex items-center gap-6">
                  <div className="w-10 text-center">
                    <p className="text-sm font-semibold text-gray-900">{month}/{day}</p>
                    <p className="text-xs text-gray-500">({weekday})</p>
                  </div>
                  <div>
                    <div className="flex items-center gap-2">
                      <p className="font-semibold text-gray-900">{event.title}</p>
                      {notOpen && (
                        <span className="text-xs px-2 py-0.5 bg-yellow-100 text-yellow-700 rounded-full font-medium">
                          판매 예정
                        </span>
                      )}
                    </div>
                    <p className="text-sm text-gray-500 mt-0.5">
                      {time} &middot;&nbsp;
                      {notOpen ? (
                        <span className="text-yellow-600 font-medium">{formatTicketOpen(event.ticketOpenAt)}</span>
                      ) : soldOut ? (
                        <span className="text-red-500 font-medium">매진</span>
                      ) : (
                        <span className="text-green-600">{event.availableSeats.toLocaleString()}석 남음</span>
                      )}
                    </p>
                  </div>
                </div>

                <div className="flex items-center gap-2">
                  {!notOpen && (
                    <button
                      onClick={() => handleTriggerBots(event.id)}
                      disabled={botLoading}
                      className="text-xs px-3 py-1.5 border border-gray-300 rounded-full text-gray-600 hover:bg-gray-50 disabled:opacity-40"
                    >
                      봇 실행
                    </button>
                  )}
                  <button
                    onClick={() => navigate(`/events/${event.id}`)}
                    disabled={soldOut || notOpen}
                    className="px-5 py-2 rounded-full text-sm font-semibold transition-colors
                      bg-blue-600 text-white hover:bg-blue-700
                      disabled:bg-gray-200 disabled:text-gray-400 disabled:cursor-not-allowed"
                  >
                    {notOpen ? '판매 예정' : soldOut ? '매진' : '예매하기 →'}
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
