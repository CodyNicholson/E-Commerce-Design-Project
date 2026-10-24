import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react'
import { api, setActingUser } from './api'
import type { CartLine, Me, User } from './types'

// Persisted per browser only; everything that matters lives in the API.
function load<T>(key: string, fallback: T): T {
  try {
    const raw = localStorage.getItem(key)
    return raw ? (JSON.parse(raw) as T) : fallback
  } catch {
    return fallback
  }
}
function save(key: string, value: unknown) {
  try {
    localStorage.setItem(key, JSON.stringify(value))
  } catch {
    /* storage unavailable */
  }
}

export interface PunchoutState {
  sessionId: string
  erp: string
  corporation: string
  userName: string
}

interface AppState {
  userId: string
  setUserId: (id: string) => void
  users: User[]
  me: Me | null
  refreshMe: () => void
  cart: CartLine[]
  addToCart: (sku: string, qty: number) => void
  setQty: (sku: string, qty: number) => void
  clearCart: () => void
  punchout: PunchoutState | null
  setPunchout: (p: PunchoutState | null) => void
  version: number
  bump: () => void
}

const Ctx = createContext<AppState | null>(null)

const initialUser = load('mro.user', 'u-dana')
setActingUser(initialUser)

export function AppProvider({ children }: { children: ReactNode }) {
  const [userId, setUserIdState] = useState<string>(initialUser)
  const [users, setUsers] = useState<User[]>([])
  const [me, setMe] = useState<Me | null>(null)
  const [cart, setCart] = useState<CartLine[]>(() => load('mro.cart', []))
  const [punchout, setPunchoutState] = useState<PunchoutState | null>(() => load('mro.punchout', null))
  // version bumps force data views to refetch after a mutation elsewhere (e.g. reset).
  const [version, setVersion] = useState(0)

  const refreshMe = useCallback(() => {
    api<Me>('/api/me').then(setMe).catch(() => setMe(null))
  }, [])

  useEffect(() => {
    api<User[]>('/api/users').then(setUsers).catch(() => setUsers([]))
  }, [version])

  useEffect(() => {
    setActingUser(userId)
    save('mro.user', userId)
    refreshMe()
  }, [userId, refreshMe, version])

  useEffect(() => save('mro.cart', cart), [cart])
  useEffect(() => save('mro.punchout', punchout), [punchout])

  const value = useMemo<AppState>(
    () => ({
      userId,
      setUserId: (id) => {
        setActingUser(id)
        setUserIdState(id)
      },
      users,
      me,
      refreshMe,
      cart,
      addToCart: (sku, qty) =>
        setCart((c) => {
          const found = c.find((l) => l.sku === sku)
          return found ? c.map((l) => (l.sku === sku ? { ...l, qty: l.qty + qty } : l)) : [...c, { sku, qty }]
        }),
      setQty: (sku, qty) => setCart((c) => (qty <= 0 ? c.filter((l) => l.sku !== sku) : c.map((l) => (l.sku === sku ? { ...l, qty } : l)))),
      clearCart: () => setCart([]),
      punchout,
      setPunchout: setPunchoutState,
      version,
      bump: () => setVersion((v) => v + 1),
    }),
    [userId, users, me, refreshMe, cart, punchout, version],
  )
  return <Ctx.Provider value={value}>{children}</Ctx.Provider>
}

export function useApp() {
  const v = useContext(Ctx)
  if (!v) throw new Error('useApp outside AppProvider')
  return v
}

// useFetch loads JSON and refetches when deps or the global version change.
export function useFetch<T>(path: string | null, deps: unknown[] = []) {
  const { version, userId } = useApp()
  const [data, setData] = useState<T | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(false)
  const [tick, setTick] = useState(0)
  useEffect(() => {
    if (!path) return
    let live = true
    setLoading(true)
    api<T>(path)
      .then((d) => {
        if (live) {
          setData(d)
          setError(null)
        }
      })
      .catch((e: Error) => live && setError(e.message))
      .finally(() => live && setLoading(false))
    return () => {
      live = false
    }
  }, [path, version, userId, tick, ...deps])
  return { data, error, loading, reload: () => setTick((t) => t + 1), setData }
}
