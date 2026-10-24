import { NavLink, Route, Routes, useNavigate } from 'react-router-dom'
import { api } from './api'
import { money0 } from './format'
import { useApp } from './state'
import Home from './pages/Home'
import Catalog from './pages/Catalog'
import ProductPage from './pages/Product'
import Cart from './pages/Cart'
import Orders from './pages/Orders'
import OrderPage from './pages/Order'
import Account from './pages/Account'
import Network from './pages/Network'
import KeepStock from './pages/KeepStock'
import EProcurement from './pages/EProcurement'
import PunchoutLanding from './pages/PunchoutLanding'

const nav = [
  ['/', 'Study Guide'],
  ['/catalog', 'Catalog'],
  ['/cart', 'Cart'],
  ['/orders', 'Orders'],
  ['/account', 'Account'],
  ['/network', 'Fulfillment'],
  ['/keepstock', 'KeepStock'],
  ['/eprocurement', 'PunchOut & EDI'],
] as const

function PersonaSwitcher() {
  const { users, userId, setUserId, punchout } = useApp()
  const corps = [...new Set(users.map((u) => u.corporation))]
  return (
    <select
      aria-label="Acting as"
      value={userId}
      disabled={!!punchout}
      onChange={(e) => setUserId(e.target.value)}
      className="max-w-[16rem] text-sm"
    >
      {corps.map((c) => (
        <optgroup key={c} label={c}>
          {users
            .filter((u) => u.corporation === c)
            .map((u) => (
              <option key={u.id} value={u.id}>
                {u.name} · {u.title} ({money0(u.perOrderLimit)})
              </option>
            ))}
        </optgroup>
      ))}
    </select>
  )
}

function PunchoutBanner() {
  const { punchout, setPunchout } = useApp()
  const navigate = useNavigate()
  if (!punchout) return null
  return (
    <div className="bg-violet-700 px-4 py-2 text-sm text-white">
      <div className="mx-auto flex max-w-7xl flex-wrap items-center justify-between gap-2">
        <span>
          <strong>PunchOut session</strong> from {punchout.erp} for {punchout.userName} ({punchout.corporation}). Your cart returns to the ERP
          instead of checking out here.
        </span>
        <button
          className="rounded border border-white/40 px-2 py-0.5 hover:bg-white/10"
          onClick={() => {
            setPunchout(null)
            navigate('/eprocurement')
          }}
        >
          Cancel session
        </button>
      </div>
    </div>
  )
}

export default function App() {
  const { me, cart, bump } = useApp()
  const cartCount = cart.reduce((n, l) => n + l.qty, 0)
  return (
    <div className="min-h-screen">
      <PunchoutBanner />
      <header className="border-b border-slate-200 bg-white dark:border-slate-800 dark:bg-slate-900">
        <div className="mx-auto flex max-w-7xl flex-wrap items-center justify-between gap-3 px-4 py-3">
          <NavLink to="/" className="flex items-center gap-2">
            <img src="/favicon.svg" alt="" className="h-7 w-7" />
            <div className="leading-tight">
              <div className="font-bold tracking-tight">MRO Mirror</div>
              <div className="text-[11px] text-slate-500 dark:text-slate-400">Industrial B2B distribution study system</div>
            </div>
          </NavLink>
          <div className="flex flex-wrap items-center gap-3">
            <div className="text-right text-xs text-slate-500 dark:text-slate-400">
              <div>Acting as</div>
              {me && <div className="max-w-[16rem] truncate">{me.orgPath.join(' › ')}</div>}
            </div>
            <PersonaSwitcher />
          </div>
        </div>
        <nav className="mx-auto flex max-w-7xl gap-1 overflow-x-auto px-4 pb-2">
          {nav.map(([to, label]) => (
            <NavLink
              key={to}
              to={to}
              end={to === '/'}
              className={({ isActive }) =>
                `rounded-md px-3 py-1.5 text-sm font-medium whitespace-nowrap ${
                  isActive ? 'bg-brand-600 text-white' : 'text-slate-600 hover:bg-slate-100 dark:text-slate-300 dark:hover:bg-slate-800'
                }`
              }
            >
              {label}
              {to === '/cart' && cartCount > 0 && <span className="ml-1.5 rounded-full bg-white/90 px-1.5 text-xs text-brand-700">{cartCount}</span>}
              {to === '/orders' && !!me?.pendingApprovals && (
                <span className="ml-1.5 rounded-full bg-amber-400 px-1.5 text-xs text-slate-900" title="Waiting for your approval">
                  {me.pendingApprovals}
                </span>
              )}
            </NavLink>
          ))}
        </nav>
      </header>
      <main className="mx-auto max-w-7xl px-4 py-6">
        <Routes>
          <Route path="/" element={<Home />} />
          <Route path="/catalog" element={<Catalog />} />
          <Route path="/product/:sku" element={<ProductPage />} />
          <Route path="/cart" element={<Cart />} />
          <Route path="/orders" element={<Orders />} />
          <Route path="/orders/:id" element={<OrderPage />} />
          <Route path="/account" element={<Account />} />
          <Route path="/network" element={<Network />} />
          <Route path="/keepstock" element={<KeepStock />} />
          <Route path="/eprocurement" element={<EProcurement />} />
          <Route path="/erp/return" element={<EProcurement />} />
          <Route path="/punchout/:id" element={<PunchoutLanding />} />
          <Route path="*" element={<div className="py-16 text-center text-slate-500">Page not found.</div>} />
        </Routes>
      </main>
      <footer className="mx-auto max-w-7xl px-4 pb-8 text-xs text-slate-500 dark:text-slate-400">
        <div className="flex flex-wrap items-center justify-between gap-2 border-t border-slate-200 pt-4 dark:border-slate-800">
          <span>All customers, SKUs, prices, contracts and stock levels are synthetic study data.</span>
          <button
            className="underline hover:text-slate-700 dark:hover:text-slate-200"
            onClick={async () => {
              if (!confirm('Reset every table to the original seed data?')) return
              await api('/api/admin/reset', { method: 'POST' })
              bump()
            }}
          >
            Reset demo data
          </button>
        </div>
      </footer>
    </div>
  )
}
