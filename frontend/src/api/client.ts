const BASE_URL = import.meta.env.VITE_API_URL ?? 'http://localhost:8080'

function getUserId(): string {
  let userId = localStorage.getItem('userId')
  if (!userId) {
    userId = crypto.randomUUID()
    localStorage.setItem('userId', userId)
  }
  return userId
}

export const userId = getUserId()

export async function get<T>(path: string): Promise<T> {
  const res = await fetch(`${BASE_URL}${path}`, {
    headers: { 'X-USER-ID': userId },
  })
  if (!res.ok) throw new Error(`GET ${path} failed: ${res.status}`)
  return res.json()
}

export async function post<T>(path: string, body?: unknown): Promise<{ status: number; data: T }> {
  const res = await fetch(`${BASE_URL}${path}`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'X-USER-ID': userId,
    },
    body: body ? JSON.stringify(body) : undefined,
  })
  const data = res.status !== 204 ? await res.json().catch(() => null) : null
  return { status: res.status, data }
}

export function createSSE(path: string, onMessage: (event: MessageEvent) => void): EventSource {
  const url = `${BASE_URL}${path}`
  const es = new EventSource(url)
  es.onmessage = onMessage
  return es
}

export { BASE_URL }
