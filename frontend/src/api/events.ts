import { get, post, deleteReq } from './client'

export interface Event {
  id: number
  title: string
  openAt: string
  totalSeats: number
  availableSeats: number
  status: 'OPEN' | 'SOLD_OUT' | 'CLOSED'
  ticketOpenAt: string
  isTicketOpen: boolean
  homeTeam: string
  awayTeam: string
}

export interface AvailableSeats {
  eventId: number
  availableCount: number
}

export const fetchEvents = () => get<Event[]>('/api/events')

export const fetchAvailableSeats = (eventId: number) =>
  get<AvailableSeats>(`/api/events/${eventId}/seats/available`)

export const joinQueue = (eventId: number) =>
  post<{ rank: number }>('/api/queue/join', { eventId })

export const createBooking = (eventId: number) =>
  post<{ seatId: number; orderId: string; amount: number; message: string }>('/api/v2/bookings', { eventId })

export const fetchBookingStatus = () =>
  get<{ status: 'WAITING' | 'SUCCESS' | 'FAIL' }>('/api/status')

export const triggerBots = (eventId: number) =>
  post<{ triggered: number }>('/api/bot/trigger', { eventId })

export const confirmPayment = (paymentKey: string, orderId: string, amount: number) =>
  post<{ seatId: number; message: string }>('/api/payments/confirm', { paymentKey, orderId, amount })

export const cancelBooking = (eventId: number, seatId: number) =>
  deleteReq('/api/v2/bookings', { eventId, seatId })
