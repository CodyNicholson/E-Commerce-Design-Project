import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { api } from '../api'
import { Badge, Button, Callout, Card, ErrorBox, H2, Loading, PageTitle, SkuLink, StatusBadge } from '../components/ui'
import { dateTime, money } from '../format'
import { useApp, useFetch } from '../state'
import type { OrderSummary } from '../types'

interface Bin {
  id: string
  costCenter: string
  sku: string
  name: string
  location: string
  minQty: number
  maxQty: number
  onHand: number
  onOrder: number
  lastScannedAt: string | null
}
interface Slot {
  machineId: string
  slot: string
  sku: string
  name: string
  onHand: number
  capacity: number
  par: number
  onOrder: number
  dailyLimit: number
}
interface KSData {
  facilityId: string
  facilities: { id: string; name: string }[]
  bins: Bin[]
  machines: { id: string; name: string; location: string; costCenter: string; slots: Slot[] }[]
  events: { id: number; machineId: string; slot: string; badgeId: string; userName: string | null; sku: string; qty: number; outcome: string; message: string; createdAt: string }[]
  orders: OrderSummary[]
  badges: { badgeId: string; name: string }[]
}

// LevelBar draws on-hand against max with min (reorder point) and on-order marked.
function LevelBar({ onHand, min, max, onOrder }: { onHand: number; min: number; max: number; onOrder: number }) {
  const pct = (v: number) => `${Math.min(v / max, 1) * 100}%`
  const low = onHand <= min
  return (
    <div className="relative h-3 w-full rounded-full bg-slate-200 dark:bg-slate-800">
      <div className={`absolute inset-y-0 left-0 rounded-full ${low ? 'bg-red-500' : 'bg-emerald-500'}`} style={{ width: pct(onHand) }} />
      {onOrder > 0 && <div className="absolute inset-y-0 rounded-r-full bg-sky-400/60" style={{ left: pct(onHand), width: pct(onOrder) }} title={`${onOrder} on order`} />}
      <div className="absolute -inset-y-0.5 w-0.5 bg-slate-900 dark:bg-white" style={{ left: pct(min) }} title={`min ${min}`} />
    </div>
  )
}

export default function KeepStock() {
  const { me, refreshMe } = useApp()
  const [facilityId, setFacilityId] = useState('')
  const { data, error, reload } = useFetch<KSData>(`/api/keepstock${facilityId ? `?facilityId=${facilityId}` : ''}`, [facilityId])
  const [counts, setCounts] = useState<Record<string, string>>({})
  const [badge, setBadge] = useState('')
  const [message, setMessage] = useState<{ text: string; tone: 'ok' | 'bad' } | null>(null)
  const [actionError, setActionError] = useState<string | null>(null)

  useEffect(() => {
    setFacilityId('')
    setMessage(null)
  }, [me?.user.id])
  useEffect(() => {
    if (data && !badge) setBadge(me?.user.badgeId ?? data.badges[0]?.badgeId ?? '')
  }, [data, badge, me?.user.badgeId])

  const run = async (fn: () => Promise<{ message?: string; outcome?: string }>) => {
    setActionError(null)
    try {
      const r = await fn()
      if (r.message) setMessage({ text: r.message, tone: r.outcome === 'denied' ? 'bad' : 'ok' })
      reload()
      refreshMe()
    } catch (e) {
      setActionError((e as Error).message)
    }
  }

  if (error) return <ErrorBox error={error} />
  if (!data) return <Loading />

  return (
    <div className="space-y-4">
      <PageTitle
        title="KeepStock: vendor-managed inventory"
        subtitle="Bins and vending machines inside the customer's plant. Cycle counts and badge swipes feed straight into the OMS. Dropping to the reorder point raises a replenishment order with no buyer involved."
      >
        <select value={data.facilityId} onChange={(e) => setFacilityId(e.target.value)} aria-label="Facility">
          {data.facilities.map((f) => (
            <option key={f.id} value={f.id}>
              {f.name}
            </option>
          ))}
        </select>
      </PageTitle>

      {message && (
        <div className={`rounded-lg border p-3 text-sm ${message.tone === 'bad' ? 'border-red-300 bg-red-50 dark:border-red-800 dark:bg-red-950' : 'border-emerald-300 bg-emerald-50 dark:border-emerald-800 dark:bg-emerald-950'}`}>
          {message.text}
        </div>
      )}
      <ErrorBox error={actionError} />

      <Card className="overflow-x-auto">
        <H2
          aside={
            <Button
              variant="secondary"
              onClick={() =>
                run(async () => {
                  const r = await api<{ orders: { orderId: string; lines: number; total: number }[]; linesConsidered: number }>('/api/keepstock/replenish', {
                    json: { facilityId: data.facilityId },
                  })
                  return {
                    message: r.orders.length
                      ? `Replenishment sweep raised ${r.orders.map((o) => `${o.orderId} (${o.lines} lines, ${money(o.total)})`).join(', ')}.`
                      : 'Sweep found nothing at or below its reorder point that is not already on order.',
                  }
                })
              }
            >
              Run replenishment sweep
            </Button>
          }
        >
          Barcode bins (cycle count)
        </H2>
        {data.bins.length === 0 ? (
          <p className="text-sm text-slate-500">No bins at this facility.</p>
        ) : (
          <table className="w-full min-w-[46rem] text-sm">
            <thead className="text-left text-xs text-slate-500 uppercase">
              <tr>
                <th className="py-1">Bin</th>
                <th className="py-1">Item</th>
                <th className="w-48 py-1">Level (min | max)</th>
                <th className="py-1">Scan count</th>
              </tr>
            </thead>
            <tbody>
              {data.bins.map((b) => (
                <tr key={b.id} className="border-t border-slate-100 align-top dark:border-slate-800">
                  <td className="py-2">
                    <div className="font-mono text-xs font-medium">{b.id}</div>
                    <div className="text-xs text-slate-500">{b.location}</div>
                    <div className="text-xs text-slate-500">{b.costCenter}</div>
                  </td>
                  <td className="py-2">
                    <SkuLink sku={b.sku} /> <span className="text-xs">{b.name}</span>
                  </td>
                  <td className="py-2 pr-3">
                    <LevelBar onHand={b.onHand} min={b.minQty} max={b.maxQty} onOrder={b.onOrder} />
                    <div className="mt-1 text-xs text-slate-500">
                      {b.onHand} on hand · min {b.minQty} · max {b.maxQty}
                      {b.onOrder > 0 && <span className="text-sky-600"> · {b.onOrder} on order</span>}
                    </div>
                    {b.lastScannedAt && <div className="text-xs text-slate-400">scanned {dateTime(b.lastScannedAt)}</div>}
                  </td>
                  <td className="py-2">
                    <form
                      className="flex gap-1"
                      onSubmit={(e) => {
                        e.preventDefault()
                        const v = Number(counts[b.id])
                        if (counts[b.id] === undefined || counts[b.id] === '' || Number.isNaN(v)) return
                        run(() => api(`/api/keepstock/bins/${b.id}/scan`, { json: { onHand: v } }))
                        setCounts((c) => ({ ...c, [b.id]: '' }))
                      }}
                    >
                      <input
                        type="number"
                        min={0}
                        placeholder={String(b.onHand)}
                        value={counts[b.id] ?? ''}
                        onChange={(e) => setCounts((c) => ({ ...c, [b.id]: e.target.value }))}
                        className="w-20"
                        aria-label={`Count for ${b.id}`}
                      />
                      <Button type="submit" variant="secondary" className="px-2 text-xs">
                        Scan
                      </Button>
                    </form>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </Card>

      <Card>
        <H2
          aside={
            <label className="flex items-center gap-2">
              Badge
              <select value={badge} onChange={(e) => setBadge(e.target.value)}>
                {data.badges.map((b) => (
                  <option key={b.badgeId} value={b.badgeId}>
                    {b.badgeId} · {b.name}
                  </option>
                ))}
                <option value="B-9999">B-9999 · unknown badge</option>
              </select>
            </label>
          }
        >
          Industrial vending (badge swipe)
        </H2>
        {data.machines.length === 0 && <p className="text-sm text-slate-500">No vending machines at this facility.</p>}
        <div className="grid gap-4 md:grid-cols-2">
          {data.machines.map((m) => (
            <div key={m.id} className="rounded-xl border-4 border-slate-700 bg-slate-800 p-3 text-slate-100 dark:border-slate-600">
              <div className="mb-2 flex items-center justify-between text-sm">
                <span className="font-semibold">{m.name}</span>
                <span className="font-mono text-xs opacity-70">{m.id}</span>
              </div>
              <div className="mb-2 text-xs opacity-70">
                {m.location} · stock owned by {m.costCenter}
              </div>
              <div className="grid grid-cols-2 gap-2 sm:grid-cols-3">
                {m.slots.map((s) => (
                  <button
                    key={s.slot}
                    onClick={() => run(() => api(`/api/keepstock/machines/${m.id}/dispense`, { json: { slot: s.slot, badgeId: badge, qty: 1 } }))}
                    className="rounded-lg bg-slate-900 p-2 text-left text-xs hover:ring-2 hover:ring-amber-400"
                    title={`Swipe ${badge} and vend 1 from ${s.slot}`}
                  >
                    <div className="flex justify-between">
                      <span className="font-mono font-bold text-amber-400">{s.slot}</span>
                      <span className="font-mono">{s.sku}</span>
                    </div>
                    <div className="mt-1 line-clamp-2 min-h-[2rem] opacity-80">{s.name}</div>
                    <div className="mt-1 h-1.5 rounded-full bg-slate-700">
                      <div className={`h-full rounded-full ${s.onHand <= s.par ? 'bg-red-500' : 'bg-emerald-500'}`} style={{ width: `${(s.onHand / s.capacity) * 100}%` }} />
                    </div>
                    <div className="mt-1 opacity-70">
                      {s.onHand}/{s.capacity} · par {s.par} · {s.dailyLimit}/day
                      {s.onOrder > 0 && <span className="text-sky-300"> · +{s.onOrder}</span>}
                    </div>
                  </button>
                ))}
              </div>
            </div>
          ))}
        </div>
        <p className="mt-2 text-xs text-slate-500">Click a slot to swipe the selected badge and vend one unit. Usage is charged to the employee's cost center.</p>
      </Card>

      <div className="grid gap-4 lg:grid-cols-2">
        <Card>
          <H2>Vending log</H2>
          {data.events.length === 0 ? (
            <p className="text-sm text-slate-500">No swipes yet.</p>
          ) : (
            <ul className="space-y-1.5 text-sm">
              {data.events.map((e) => (
                <li key={e.id} className="flex gap-2">
                  <Badge tone={e.outcome === 'dispensed' ? 'green' : 'red'}>{e.outcome}</Badge>
                  <span>
                    <span className="text-xs text-slate-500">
                      {dateTime(e.createdAt)} · {e.machineId}/{e.slot} · {e.badgeId}
                    </span>{' '}
                    {e.message}
                  </span>
                </li>
              ))}
            </ul>
          )}
        </Card>
        <Card>
          <H2>Replenishment orders</H2>
          {data.orders.length === 0 ? (
            <p className="text-sm text-slate-500">None yet. Scan a bin at or below min, or vend a slot down to par.</p>
          ) : (
            <table className="w-full text-sm">
              <tbody>
                {data.orders.map((o) => (
                  <tr key={o.id} className="border-t border-slate-100 dark:border-slate-800">
                    <td className="py-1.5">
                      <Link to={`/orders/${o.id}`} className="font-mono text-brand-600 hover:underline dark:text-amber-400">
                        {o.id}
                      </Link>
                      <div className="text-xs text-slate-500">
                        {dateTime(o.createdAt)} · {o.costCenter}
                      </div>
                    </td>
                    <td className="py-1.5">
                      <StatusBadge status={o.status} />
                    </td>
                    <td className="py-1.5 text-right tabular-nums">{money(o.total)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
          <div className="mt-3">
            <Callout tone="blue">Open a replenishment order and choose “Simulate pick & ship”. The bin or slot refills and its on-order quantity clears.</Callout>
          </div>
        </Card>
      </div>
    </div>
  )
}
