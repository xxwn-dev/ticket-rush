import { Routes, Route } from 'react-router-dom'
import EventListPage from './pages/EventListPage'
import EventDetailPage from './pages/EventDetailPage'
import PaymentSuccessPage from './pages/PaymentSuccessPage'
import PaymentFailPage from './pages/PaymentFailPage'
import AdminPage from './pages/AdminPage'

export default function App() {
  return (
    <Routes>
      <Route path="/" element={<EventListPage />} />
      <Route path="/events/:id" element={<EventDetailPage />} />
      <Route path="/payment/success" element={<PaymentSuccessPage />} />
      <Route path="/payment/fail" element={<PaymentFailPage />} />
      <Route path="/admin" element={<AdminPage />} />
    </Routes>
  )
}
