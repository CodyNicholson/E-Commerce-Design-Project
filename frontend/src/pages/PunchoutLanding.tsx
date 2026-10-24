import { useEffect, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { api } from '../api'
import { ErrorBox, Loading } from '../components/ui'
import { useApp } from '../state'

interface Session {
  id: string
  erp: string
  userId: string
  userName: string
  corporation: string
  status: string
}

// The StartPage URL returned to the ERP lands here: adopt the session's buyer and shop.
export default function PunchoutLanding() {
  const { id = '' } = useParams()
  const { setUserId, setPunchout, clearCart } = useApp()
  const navigate = useNavigate()
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    api<Session>(`/api/punchout/sessions/${id}`)
      .then((s) => {
        if (s.status !== 'open') throw new Error('This PunchOut session has already returned its cart to the ERP.')
        clearCart()
        setUserId(s.userId)
        setPunchout({ sessionId: s.id, erp: s.erp, corporation: s.corporation, userName: s.userName })
        navigate('/catalog', { replace: true })
      })
      .catch((e: Error) => setError(e.message))
  }, [id])

  return error ? <ErrorBox error={error} /> : <Loading />
}
