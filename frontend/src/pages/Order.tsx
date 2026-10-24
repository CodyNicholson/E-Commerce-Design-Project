import { useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { api } from '../api'
import { Badge, Button, Card, Checks, ErrorBox, H2, Loading, SkuLink, StatusBadge } from '../components/ui'
import { dateTime, methodLabel, money, sourceLabel } from '../format'
import { useApp, useFetch } from '../state'
import type { OrderDetail } from '../types'

export default function OrderPage() {
  const { id = '' } = useParams()
  const { me, refreshMe } = useApp()
  const { data, error, setData } = useFetch<OrderDetail>(`/api/orders/${id}`)
  const [note, setNote] = useState('')
  const [actionError, setActionError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  const act = async (action: 'approve' | 'reject' | 'ship') => {
    setBusy(true)
    setActionError(null)
    try {
      setData(await api<OrderDetail>(`/api/orders/${id}/${action}`, { json: { note } }))
      setNote('')
      refreshMe()
    } catch (e) {
      setActionError((e as Error).message)
    } finally {
      setBusy(false)
    }
  }

  if (error) return <ErrorBox error={error} />
  if (!data) return <Loading />
  const o = data.order
  const canApprove = o.status === 'pending_approval' && me?.user.id === o.approverId

  return (
    <div className="space-y-4">
      <Link to="/orders" className="text-sm text-slate-500 hover:underline">
        ← Orders
      </Link>
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h1 className="flex items-center gap-2 font-mono text-2xl font-bold">
            {o.id} <StatusBadge status={o.status} />
          </h1>
          <div className="mt-1 text-sm text-slate-600 dark:text-slate-400">
            {sourceLabel[o.source]} order by {o.userName} · {dateTime(o.createdAt)} · charged to {o.costCenter}, {o.facility}
            {o.poNumber && <> · PO {o.poNumber}</>}
          </div>
        </div>
        <div className="text-right">
          <div className="text-2xl font-bold tabular-nums">{money(o.total)}</div>
          <div className="text-xs text-slate-500">
            {money(data.subtotal)} + tax {money(data.tax)} ({data.taxNote})
          </div>
        </div>
      </div>

      {canApprove && (
        <Card className="border-amber-400 dark:border-amber-700">
          <H2>Your approval is needed</H2>
          <p className="mb-2 text-sm text-slate-600 dark:text-slate-400">
            Your per-order authority is {money(me!.user.perOrderLimit)}.{' '}
            {o.total > me!.user.perOrderLimit ? 'This order is above it, so approving escalates it to the next approver with enough authority.' : 'Approving releases the order to the warehouses.'}
          </p>
          <div className="flex flex-wrap gap-2">
            <input value={note} onChange={(e) => setNote(e.target.value)} placeholder="Note (optional)" className="min-w-0 flex-1" />
            <Button disabled={busy} onClick={() => act('approve')}>
              {o.total > me!.user.perOrderLimit ? 'Approve & escalate' : 'Approve'}
            </Button>
            <Button variant="danger" disabled={busy} onClick={() => act('reject')}>
              Reject
            </Button>
          </div>
        </Card>
      )}
      {o.status === 'pending_approval' && !canApprove && (
        <Card>
          <p className="text-sm">
            Waiting on <strong>{o.approverName}</strong>. Switch persona to them to act on it.
          </p>
        </Card>
      )}
      <ErrorBox error={actionError} />

      <div className="grid gap-4 lg:grid-cols-2">
        <Card className="overflow-x-auto">
          <H2>Lines</H2>
          <table className="w-full text-sm">
            <thead className="text-left text-xs text-slate-500 uppercase">
              <tr>
                <th className="py-1">Item</th>
                <th className="py-1 pl-3 text-right">Qty</th>
                <th className="py-1 pl-3 text-right">Unit</th>
                <th className="py-1 pl-3 text-right">Ext.</th>
              </tr>
            </thead>
            <tbody>
              {data.lines.map((l) => (
                <tr key={l.lineNo} className="border-t border-slate-100 align-top dark:border-slate-800">
                  <td className="py-1.5">
                    <SkuLink sku={l.sku} /> {l.name}
                    <div className="text-xs text-slate-500">
                      {l.priceSource}
                      {l.ref && <> · refills {l.ref}</>}
                    </div>
                  </td>
                  <td className="py-1.5 pl-3 text-right whitespace-nowrap tabular-nums">
                    {l.qty} {l.uom}
                  </td>
                  <td className="py-1.5 pl-3 text-right tabular-nums">{money(l.unitPrice)}</td>
                  <td className="py-1.5 pl-3 text-right tabular-nums">{money(l.extended)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </Card>

        <Card>
          <H2
            aside={
              o.status === 'released' && (
                <Button variant="secondary" disabled={busy} onClick={() => act('ship')}>
                  Simulate pick & ship
                </Button>
              )
            }
          >
            Shipments
          </H2>
          {data.shipments.length === 0 ? (
            <p className="text-sm text-slate-500">
              {o.status === 'pending_approval' ? 'Stock is allocated when the order is released, not while it waits for approval.' : 'No shipments.'}
            </p>
          ) : (
            <div className="space-y-2">
              {data.shipments.map((s) => (
                <div key={s.id} className="rounded-lg border border-slate-200 p-3 text-sm dark:border-slate-800">
                  <div className="flex flex-wrap items-center justify-between gap-2">
                    <span className="font-medium">{s.nodeName ?? 'Backorder'}</span>
                    <span className="flex gap-1">
                      <Badge>{methodLabel[s.method] ?? s.method}</Badge>
                      <Badge tone={s.status === 'shipped' ? 'green' : s.status === 'backordered' ? 'amber' : 'blue'}>{s.status}</Badge>
                    </span>
                  </div>
                  <div className="text-xs text-slate-500">
                    {s.etaLabel}
                    {s.note && ` · ${s.note}`}
                  </div>
                  <div className="mt-1 font-mono text-xs">{s.lines.map((l) => `${l.qty} × ${l.sku}`).join(', ')}</div>
                </div>
              ))}
            </div>
          )}
        </Card>

        <Card>
          <H2>Governance checks at submission</H2>
          {data.governance.length ? <Checks checks={data.governance} /> : <p className="text-sm text-slate-500">Historical order; no checks recorded.</p>}
        </Card>

        <Card>
          <H2>Approval history</H2>
          {data.approvals.length === 0 ? (
            <p className="text-sm text-slate-500">{o.status === 'pending_approval' ? 'No decisions yet.' : 'Released without manual approval.'}</p>
          ) : (
            <ol className="space-y-2 text-sm">
              {data.approvals.map((a, i) => (
                <li key={i} className="border-l-2 border-slate-300 pl-3 dark:border-slate-700">
                  <div>
                    <strong>{a.byName}</strong> <Badge tone={a.action === 'rejected' ? 'red' : a.action === 'escalated' ? 'amber' : 'green'}>{a.action}</Badge>
                    <span className="ml-1 text-xs text-slate-500">{dateTime(a.at)}</span>
                  </div>
                  {a.note && <div className="text-slate-600 dark:text-slate-400">{a.note}</div>}
                </li>
              ))}
            </ol>
          )}
        </Card>
      </div>
    </div>
  )
}
