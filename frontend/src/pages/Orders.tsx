import { Link, useSearchParams } from 'react-router-dom'
import { Badge, Card, ErrorBox, Loading, PageTitle, StatusBadge } from '../components/ui'
import { dateTime, money, sourceLabel } from '../format'
import { useApp, useFetch } from '../state'
import type { OrderSummary } from '../types'

const scopes = [
  ['mine', 'My orders'],
  ['approvals', 'Awaiting my approval'],
  ['corporation', 'All company orders'],
] as const

export default function Orders() {
  const [params, setParams] = useSearchParams()
  const { me } = useApp()
  const scope = params.get('scope') ?? (me?.pendingApprovals ? 'approvals' : 'mine')
  const { data, error } = useFetch<OrderSummary[]>(`/api/orders?scope=${scope}`)

  return (
    <div>
      <PageTitle title="Orders & approvals" subtitle="Orders from every channel land in the same OMS: web checkout, EDI 850 from ERPs, and KeepStock auto-replenishment." />
      <div className="mb-3 flex flex-wrap gap-1">
        {scopes.map(([id, label]) => (
          <button
            key={id}
            onClick={() => setParams({ scope: id })}
            className={`rounded-md px-3 py-1.5 text-sm font-medium ${scope === id ? 'bg-slate-800 text-white dark:bg-slate-200 dark:text-slate-900' : 'hover:bg-slate-200 dark:hover:bg-slate-800'}`}
          >
            {label}
            {id === 'approvals' && !!me?.pendingApprovals && <span className="ml-1.5 rounded-full bg-amber-400 px-1.5 text-xs text-slate-900">{me.pendingApprovals}</span>}
          </button>
        ))}
      </div>
      <ErrorBox error={error} />
      {!data ? (
        <Loading />
      ) : data.length === 0 ? (
        <Card>
          <p className="text-sm text-slate-500">{scope === 'approvals' ? 'Nothing is waiting for your approval.' : 'No orders yet.'}</p>
        </Card>
      ) : (
        <Card className="overflow-x-auto p-0">
          <table className="w-full min-w-[48rem] text-sm">
            <thead className="bg-slate-50 text-left text-xs text-slate-500 uppercase dark:bg-slate-800/50">
              <tr>
                <th className="px-4 py-2">Order</th>
                <th className="px-2 py-2">Placed</th>
                <th className="px-2 py-2">Buyer</th>
                <th className="px-2 py-2">Cost center</th>
                <th className="px-2 py-2">Channel</th>
                <th className="px-2 py-2">Status</th>
                <th className="px-4 py-2 text-right">Total</th>
              </tr>
            </thead>
            <tbody>
              {data.map((o) => (
                <tr key={o.id} className="border-t border-slate-100 dark:border-slate-800">
                  <td className="px-4 py-2">
                    <Link to={`/orders/${o.id}`} className="font-mono font-medium text-brand-600 hover:underline dark:text-amber-400">
                      {o.id}
                    </Link>
                    {o.poNumber && <div className="text-xs text-slate-500">PO {o.poNumber}</div>}
                  </td>
                  <td className="px-2 py-2 text-xs">{dateTime(o.createdAt)}</td>
                  <td className="px-2 py-2">{o.userName}</td>
                  <td className="px-2 py-2 text-xs">
                    {o.costCenter}
                    <div className="text-slate-500">{o.facility}</div>
                  </td>
                  <td className="px-2 py-2">
                    <Badge>{sourceLabel[o.source]}</Badge>
                  </td>
                  <td className="px-2 py-2">
                    <StatusBadge status={o.status} />
                    {o.status === 'pending_approval' && o.approverName && <div className="mt-0.5 text-xs text-slate-500">with {o.approverName}</div>}
                  </td>
                  <td className="px-4 py-2 text-right font-medium tabular-nums">{money(o.total)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </Card>
      )}
    </div>
  )
}
