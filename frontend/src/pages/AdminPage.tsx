import { useState } from 'react'
import { importKboEvents, resetData } from '../api/admin'

export default function AdminPage() {
  const currentYear = new Date().getFullYear()
  const currentMonth = new Date().getMonth() + 1

  const [year, setYear] = useState(currentYear)
  const [month, setMonth] = useState(currentMonth)
  const [importing, setImporting] = useState(false)
  const [resetting, setResetting] = useState(false)
  const [importResult, setImportResult] = useState<string | null>(null)
  const [resetResult, setResetResult] = useState<string | null>(null)

  const handleImport = async () => {
    setImporting(true)
    setImportResult(null)
    try {
      const { data } = await importKboEvents(year, month)
      setImportResult(`${data.imported}개 경기 등록 완료`)
    } catch {
      setImportResult('임포트 실패')
    } finally {
      setImporting(false)
    }
  }

  const handleReset = async () => {
    if (!confirm('모든 데이터를 초기화하고 KBO 일정을 다시 불러옵니다. 계속하시겠습니까?')) return
    setResetting(true)
    setResetResult(null)
    try {
      await resetData()
      setResetResult('초기화 완료')
    } catch {
      setResetResult('초기화 실패')
    } finally {
      setResetting(false)
    }
  }

  return (
    <div style={{ maxWidth: 480, margin: '60px auto', padding: '0 24px', fontFamily: 'sans-serif' }}>
      <h1 style={{ fontSize: 24, fontWeight: 700, marginBottom: 32 }}>관리자 페이지</h1>

      <section style={{ marginBottom: 40 }}>
        <h2 style={{ fontSize: 16, fontWeight: 600, marginBottom: 12 }}>KBO 일정 불러오기</h2>
        <div style={{ display: 'flex', gap: 8, marginBottom: 12 }}>
          <select value={year} onChange={e => setYear(Number(e.target.value))}
            style={{ padding: '6px 12px', borderRadius: 6, border: '1px solid #ccc' }}>
            {[currentYear - 1, currentYear, currentYear + 1].map(y => (
              <option key={y} value={y}>{y}년</option>
            ))}
          </select>
          <select value={month} onChange={e => setMonth(Number(e.target.value))}
            style={{ padding: '6px 12px', borderRadius: 6, border: '1px solid #ccc' }}>
            {Array.from({ length: 12 }, (_, i) => i + 1).map(m => (
              <option key={m} value={m}>{m}월</option>
            ))}
          </select>
          <button onClick={handleImport} disabled={importing}
            style={{ padding: '6px 16px', borderRadius: 6, background: '#1a56db', color: '#fff', border: 'none', cursor: 'pointer' }}>
            {importing ? '불러오는 중...' : '불러오기'}
          </button>
        </div>
        {importResult && <p style={{ color: importResult.includes('실패') ? '#e02424' : '#057a55' }}>{importResult}</p>}
      </section>

      <section>
        <h2 style={{ fontSize: 16, fontWeight: 600, marginBottom: 12 }}>데이터 초기화</h2>
        <button onClick={handleReset} disabled={resetting}
          style={{ padding: '8px 20px', borderRadius: 6, background: '#e02424', color: '#fff', border: 'none', cursor: 'pointer' }}>
          {resetting ? '초기화 중...' : '데이터 초기화'}
        </button>
        {resetResult && <p style={{ marginTop: 8, color: resetResult.includes('실패') ? '#e02424' : '#057a55' }}>{resetResult}</p>}
      </section>
    </div>
  )
}
