import { post } from './client'

const ADMIN_TOKEN = 'ticket-rush-admin-secret'
const authHeader = { Authorization: `Bearer ${ADMIN_TOKEN}` }

export const resetData = () =>
  post<{ reset: boolean }>('/api/admin/reset', {}, authHeader)

export const importKboEvents = (year: number, month: number) =>
  post<{ imported: number; eventIds: number[] }>(
    `/api/admin/events/kbo?year=${year}&month=${month}`,
    {},
    authHeader
  )
