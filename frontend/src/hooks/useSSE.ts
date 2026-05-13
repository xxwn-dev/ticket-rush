import { useEffect, useRef } from 'react'
import { BASE_URL } from '../api/client'
import { userId } from '../api/client'

interface SSEHandlers {
  onGoBooking: () => void
  onRankUpdate?: (rank: number) => void
}

export function useSSE({ onGoBooking, onRankUpdate }: SSEHandlers, enabled: boolean) {
  const esRef = useRef<EventSource | null>(null)

  useEffect(() => {
    if (!enabled) return

    const es = new EventSource(`${BASE_URL}/api/subscribe?userId=${userId}`)
    esRef.current = es

    es.addEventListener('queue', (e) => {
      try {
        const data = JSON.parse((e as MessageEvent).data)
        if (data.status === 'GO_BOOKING') {
          onGoBooking()
          es.close()
        } else if (data.status === 'WAITING' && onRankUpdate && data.rank != null) {
          onRankUpdate(data.rank)
        }
      } catch {
        // ignore parse errors
      }
    })

    es.onerror = () => {
      es.close()
    }

    return () => {
      es.close()
      esRef.current = null
    }
  }, [enabled, onGoBooking, onRankUpdate])
}
