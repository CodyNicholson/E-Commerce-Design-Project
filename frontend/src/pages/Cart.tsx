import { useEffect, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { api } from '../api'
import { Badge, Button, Card, Checks, ErrorBox, Field, H2, Meter, PageTitle, PlanView, SkuLink } from '../components/ui'
import { money, money0 } from '../format'
import { useApp } from '../state'
import type { Evaluation } from '../types'

const outcomeText = {
  auto_approved: { label: 'Auto-approved: releases to the warehouse on submit', tone: 'green' as const },
  approval_required: { label: 'Requires approval', tone: 'amber' as const },
  blocked: { label: 'Blocked by purchasing policy', tone: 'red' as const },
}

export default function Cart() {
  const { cart, setQty, clearCart, me, userId, punchout, setPunchout, refreshMe } = useApp()
  const navigate = useNavigate()
  const [costCenterId, setCostCenterId] = useState('')
  const [poNumber, setPoNumber] = useState('')
  const [blanketPoId, setBlanketPoId] = useState('')
  const [deliveryMethod, setDeliveryMethod] = useState<'ship' | 'will_call'>('ship')
  const [fulfillmentPref, setFulfillmentPref] = useState<'ship_available' | 'ship_complete'>('ship_available')
  const [ev, setEv] = useState<Evaluation | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  // Reset account-specific choices when the persona changes.
  useEffect(() => {
    setCostCenterId(me?.user.costCenterId ?? '')
    setBlanketPoId('')
  }, [me?.user.id, me?.user.costCenterId])

  const body = { lines: cart, costCenterId, poNumber, blanketPoId, deliveryMethod, fulfillmentPref }
  const bodyKey = JSON.stringify(body)

  useEffect(() => {
    if (cart.length === 0 || !costCenterId) {
      setEv(null)
      return
    }
    const t = setTimeout(() => {
      api<Evaluation>('/api/orders/preview', { json: body })
        .then((d) => {
          setEv(d)
          setError(null)
        })
        .catch((e: Error) => setError(e.message))
    }, 250)
    return () => clearTimeout(t)
    // bodyKey captures every input of the preview.
  }, [bodyKey, userId])

  const placeOrder = async () => {
    setBusy(true)
    try {
      const res = await api<{ orderId: string }>('/api/orders', { json: body })
      clearCart()
      refreshMe()
      navigate(`/orders/${res.orderId}`)
    } catch (e) {
      setError((e as Error).message)
    } finally {
      setBusy(false)
    }
  }

  const transferToErp = async () => {
    if (!punchout) return
    setBusy(true)
    try {
      const res = await api<unknown>(`/api/punchout/sessions/${punchout.sessionId}/return`, { json: { lines: cart } })
      sessionStorage.setItem('mro.erpReturn', JSON.stringify(res))
      clearCart()
      setPunchout(null)
      navigate('/erp/return')
    } catch (e) {
      setError((e as Error).message)
    } finally {
      setBusy(false)
    }
  }

  if (cart.length === 0) {
    return (
      <div>
        <PageTitle title="Cart" />
        <Card>
          <p className="text-sm text-slate-600 dark:text-slate-400">
            Your cart is empty. <Link to="/catalog" className="text-brand-600 hover:underline">Browse the catalog</Link> or start from a scenario in the{' '}
            <Link to="/" className="text-brand-600 hover:underline">study guide</Link>.
          </p>
        </Card>
      </div>
    )
  }

  const priced = new Map(ev?.lines.map((l) => [l.sku, l]))
  const facilityCCs = me?.costCenters ?? []

  return (
    <div>
      <PageTitle
        title={punchout ? `PunchOut cart · ${punchout.erp}` : 'Cart & checkout'}
        subtitle="The preview runs the full pipeline (contract pricing, tax exemptions, purchasing governance and multi-node sourcing) without committing anything."
      />
      <div className="grid gap-4 lg:grid-cols-[1fr_24rem]">
        <div className="min-w-0 space-y-4">
          <Card className="overflow-x-auto p-0">
            <table className="w-full min-w-[36rem] text-sm">
              <thead className="bg-slate-50 text-left text-xs text-slate-500 uppercase dark:bg-slate-800/50">
                <tr>
                  <th className="px-4 py-2">Item</th>
                  <th className="px-2 py-2">Qty</th>
                  <th className="px-2 py-2">Price basis</th>
                  <th className="px-2 py-2 text-right">Unit</th>
                  <th className="px-4 py-2 text-right">Extended</th>
                </tr>
              </thead>
              <tbody>
                {cart.map((l) => {
                  const p = priced.get(l.sku)
                  return (
                    <tr key={l.sku} className="border-t border-slate-100 align-top dark:border-slate-800">
                      <td className="px-4 py-2">
                        <SkuLink sku={l.sku} /> <span>{p?.name}</span>
                        <div className="mt-0.5 flex gap-1">
                          {p?.hazmat && <Badge tone="red">Hazmat</Badge>}
                          {p?.dropShipOnly && <Badge tone="violet">Drop-ship</Badge>}
                          {p?.restriction && <Badge tone="amber">Requires {p.restriction.cert}</Badge>}
                        </div>
                      </td>
                      <td className="px-2 py-2">
                        <div className="flex items-center gap-1">
                          <input type="number" min={0} value={l.qty} onChange={(e) => setQty(l.sku, Number(e.target.value))} className="w-20" aria-label={`Quantity of ${l.sku}`} />
                          <button onClick={() => setQty(l.sku, 0)} className="px-1 text-slate-400 hover:text-red-600" aria-label={`Remove ${l.sku}`}>
                            ✕
                          </button>
                        </div>
                      </td>
                      <td className="px-2 py-2 text-xs text-slate-500">{p?.price.label}</td>
                      <td className="px-2 py-2 text-right tabular-nums">{p ? money(p.unitPrice) : '…'}</td>
                      <td className="px-4 py-2 text-right font-medium tabular-nums">{p ? money(p.extended) : '…'}</td>
                    </tr>
                  )
                })}
              </tbody>
            </table>
          </Card>

          <ErrorBox error={error} />

          {ev && punchout && (
            <Card>
              <H2>Approvals</H2>
              <p className="text-sm text-slate-600 dark:text-slate-400">
                In a PunchOut session nothing is ordered here. The priced cart goes back to {punchout.erp} as a cXML PunchOutOrderMessage, the
                requisition is approved inside the ERP, and the ERP then sends the purchase order (EDI 850).
              </p>
            </Card>
          )}
          {ev && !punchout && (
            <>
              <Card>
                <H2 aside={<Badge tone={outcomeText[ev.governance.outcome].tone}>{outcomeText[ev.governance.outcome].label}</Badge>}>
                  Purchasing governance
                </H2>
                <Checks checks={ev.governance.checks} />
                {me && (
                  <div className="mt-3 grid gap-3 text-xs sm:grid-cols-2">
                    <div>
                      <div className="mb-1 flex justify-between">
                        <span>This order vs per-order limit</span>
                        <span>
                          {money0(ev.total)} / {money0(me.user.perOrderLimit)}
                        </span>
                      </div>
                      <Meter value={ev.total} max={me.user.perOrderLimit} />
                    </div>
                    <div>
                      <div className="mb-1 flex justify-between">
                        <span>Month-to-date incl. this order</span>
                        <span>
                          {money0(ev.mtdSpend + ev.total)} / {money0(me.user.monthlyLimit)}
                        </span>
                      </div>
                      <Meter value={ev.mtdSpend + ev.total} max={me.user.monthlyLimit} />
                    </div>
                  </div>
                )}
              </Card>
            </>
          )}
          {ev && (
            <Card>
              <H2 aside={ev.plan.split ? 'split order' : 'single shipment'}>Sourcing plan (available-to-promise)</H2>
              <PlanView plan={ev.plan} />
            </Card>
          )}
        </div>

        <div className="space-y-4">
          <Card className="space-y-3">
            <H2>{punchout ? 'Return to ERP' : 'Checkout'}</H2>
            <Field label="Charge to cost center" hint={ev ? `Ship to ${ev.facility.name}, ${ev.facility.state}` : undefined}>
              <select value={costCenterId} onChange={(e) => setCostCenterId(e.target.value)} className="w-full">
                {[...new Set(facilityCCs.map((c) => c.facility))].map((f) => (
                  <optgroup key={f} label={f}>
                    {facilityCCs
                      .filter((c) => c.facility === f)
                      .map((c) => (
                        <option key={c.id} value={c.id}>
                          {c.code} · {c.name}
                        </option>
                      ))}
                  </optgroup>
                ))}
              </select>
            </Field>
            {!punchout && (
              <>
                <Field label="Blanket purchase order">
                  <select value={blanketPoId} onChange={(e) => setBlanketPoId(e.target.value)} className="w-full">
                    <option value="">None (use PO number)</option>
                    {me?.blanketPOs.map((b) => (
                      <option key={b.id} value={b.id}>
                        {b.poNumber} · {money0(b.authorized - b.consumed)} left
                      </option>
                    ))}
                  </select>
                </Field>
                {!blanketPoId && (
                  <Field label="PO number">
                    <input value={poNumber} onChange={(e) => setPoNumber(e.target.value)} className="w-full" placeholder="Optional" />
                  </Field>
                )}
              </>
            )}
            <Field label="Delivery">
              <div className="flex gap-3">
                {(['ship', 'will_call'] as const).map((m) => (
                  <label key={m} className="flex items-center gap-1.5">
                    <input type="radio" checked={deliveryMethod === m} onChange={() => setDeliveryMethod(m)} className="p-0" />
                    {m === 'ship' ? 'Ship to facility' : 'Will-call pickup'}
                  </label>
                ))}
              </div>
            </Field>
            <Field label="If not everything is in one place">
              <div className="flex flex-col gap-1">
                <label className="flex items-center gap-1.5">
                  <input type="radio" checked={fulfillmentPref === 'ship_available'} onChange={() => setFulfillmentPref('ship_available')} className="p-0" />
                  Ship what's available now (split)
                </label>
                <label className="flex items-center gap-1.5">
                  <input type="radio" checked={fulfillmentPref === 'ship_complete'} onChange={() => setFulfillmentPref('ship_complete')} className="p-0" />
                  Ship complete (consolidate)
                </label>
              </div>
            </Field>
          </Card>

          {ev && (
            <Card>
              <dl className="space-y-1 text-sm">
                <div className="flex justify-between">
                  <dt>Subtotal</dt>
                  <dd className="tabular-nums">{money(ev.subtotal)}</dd>
                </div>
                <div className="flex justify-between gap-2">
                  <dt>
                    Tax <span className="text-xs text-slate-500">{ev.taxNote}</span>
                  </dt>
                  <dd className="tabular-nums">{money(ev.tax)}</dd>
                </div>
                <div className="flex justify-between border-t border-slate-200 pt-1 text-base font-semibold dark:border-slate-800">
                  <dt>Total</dt>
                  <dd className="tabular-nums">{money(ev.total)}</dd>
                </div>
              </dl>
              {punchout ? (
                <Button className="mt-3 w-full bg-violet-700 hover:bg-violet-800" disabled={busy} onClick={transferToErp}>
                  Transfer cart to {punchout.erp}
                </Button>
              ) : (
                <Button className="mt-3 w-full" disabled={busy || ev.governance.outcome === 'blocked'} onClick={placeOrder}>
                  {ev.governance.outcome === 'approval_required' ? `Submit for approval (${ev.governance.approver?.name})` : 'Place order'}
                </Button>
              )}
            </Card>
          )}
        </div>
      </div>
    </div>
  )
}
