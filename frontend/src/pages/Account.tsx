import { Badge, Card, ErrorBox, H2, Loading, Meter, PageTitle, SkuLink } from '../components/ui'
import { date, money, money0 } from '../format'
import { useApp, useFetch } from '../state'
import type { BlanketPO, User } from '../types'

interface OrgNode {
  id: string
  kind: string
  name: string
  code: string
  state: string | null
  city: string | null
  children?: OrgNode[]
}

interface AccountData {
  tree: OrgNode
  users: (User & { mtdSpend: number })[]
  blanketPOs: BlanketPO[]
  taxExemptions: { state: string; certificate: string; type: string; expiresOn: string; valid: boolean }[]
  restrictions: { categoryId: string; category: string; cert: string }[]
  contractPrices: { sku: string; name: string; listPrice: number; price: number; ref: string; validTo: string }[]
  categoryDiscounts: { categoryId: string; category: string; pct: number }[]
}

const kindLabel: Record<string, string> = { corporation: 'Corporation', division: 'Division', facility: 'Facility', cost_center: 'Cost center' }

function OrgTree({ node, users, depth = 0 }: { node: OrgNode; users: AccountData['users']; depth?: number }) {
  const here = users.filter((u) => (node.kind === 'cost_center' ? u.costCenterId === node.id : false))
  return (
    <div className={depth ? 'mt-2 ml-4 border-l border-slate-200 pl-3 dark:border-slate-800' : ''}>
      <div className="flex flex-wrap items-center gap-2">
        <Badge tone={node.kind === 'cost_center' ? 'blue' : node.kind === 'facility' ? 'green' : 'slate'}>{kindLabel[node.kind]}</Badge>
        <span className="font-medium">{node.name}</span>
        <span className="font-mono text-xs text-slate-500">{node.code}</span>
        {node.city && (
          <span className="text-xs text-slate-500">
            {node.city}, {node.state}
          </span>
        )}
      </div>
      {here.map((u) => (
        <div key={u.id} className="mt-1 ml-4 text-xs text-slate-600 dark:text-slate-400">
          👤 {u.name}, {u.title}
        </div>
      ))}
      {node.children?.map((c) => <OrgTree key={c.id} node={c} users={users} depth={depth + 1} />)}
    </div>
  )
}

export default function Account() {
  const { me } = useApp()
  const { data, error } = useFetch<AccountData>('/api/accounts')
  if (error) return <ErrorBox error={error} />
  if (!data || !me) return <Loading />
  const byId = new Map(data.users.map((u) => [u.id, u]))

  return (
    <div className="space-y-4">
      <PageTitle
        title={me.user.corporation}
        subtitle="The customer is a hierarchy, not a person. Limits, approval chains, blanket POs, certification-gated categories, tax exemptions and negotiated pricing all hang off it."
      />
      <div className="grid gap-4 lg:grid-cols-2">
        <Card>
          <H2>Corporate hierarchy</H2>
          <OrgTree node={data.tree} users={data.users} />
        </Card>
        <Card className="overflow-x-auto">
          <H2>Buyers, limits & approval chain</H2>
          <table className="w-full min-w-[30rem] text-sm">
            <thead className="text-left text-xs text-slate-500 uppercase">
              <tr>
                <th className="py-1">Person</th>
                <th className="py-1 text-right">Per order</th>
                <th className="py-1">Month to date</th>
              </tr>
            </thead>
            <tbody>
              {data.users.map((u) => (
                <tr key={u.id} className={`border-t border-slate-100 align-top dark:border-slate-800 ${u.id === me.user.id ? 'bg-brand-50/60 dark:bg-amber-950/20' : ''}`}>
                  <td className="py-1.5">
                    <div className="font-medium">{u.name}</div>
                    <div className="text-xs text-slate-500">
                      {u.title}
                      {u.approverId && <> · approver: {byId.get(u.approverId)?.name}</>}
                    </div>
                    <div className="mt-0.5 flex flex-wrap gap-1">
                      {u.certifications.map((c) => (
                        <Badge key={c} tone="green">
                          {c}
                        </Badge>
                      ))}
                      {u.badgeId && <Badge>badge {u.badgeId}</Badge>}
                    </div>
                  </td>
                  <td className="py-1.5 text-right tabular-nums">{money0(u.perOrderLimit)}</td>
                  <td className="w-40 py-1.5 pl-3">
                    <Meter value={u.mtdSpend} max={u.monthlyLimit} />
                    <div className="mt-0.5 text-xs text-slate-500 tabular-nums">
                      {money0(u.mtdSpend)} / {money0(u.monthlyLimit)}
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </Card>

        <Card>
          <H2>Blanket purchase orders</H2>
          {data.blanketPOs.length === 0 && <p className="text-sm text-slate-500">None.</p>}
          <div className="space-y-3">
            {data.blanketPOs.map((b) => {
              const expired = new Date(b.validTo) < new Date()
              return (
                <div key={b.id}>
                  <div className="flex flex-wrap items-center justify-between gap-2 text-sm">
                    <span className="font-mono font-medium">{b.poNumber}</span>
                    {expired ? <Badge tone="red">expired {date(b.validTo)}</Badge> : <Badge tone="green">valid to {date(b.validTo)}</Badge>}
                  </div>
                  <Meter value={b.consumed} max={b.authorized} />
                  <div className="mt-0.5 text-xs text-slate-500">
                    {money(b.consumed)} consumed of {money(b.authorized)} · {money(b.authorized - b.consumed)} remaining
                  </div>
                </div>
              )
            })}
          </div>
        </Card>

        <Card>
          <H2>Policy & compliance</H2>
          <div className="text-sm font-semibold">Certification-gated categories</div>
          <ul className="mt-1 mb-3 space-y-1 text-sm">
            {data.restrictions.length === 0 && <li className="text-slate-500">No restrictions.</li>}
            {data.restrictions.map((r) => (
              <li key={r.categoryId}>
                {r.category} → requires <Badge tone="amber">{r.cert}</Badge>
              </li>
            ))}
          </ul>
          <div className="text-sm font-semibold">Sales-tax exemption certificates</div>
          <ul className="mt-1 space-y-1 text-sm">
            {data.taxExemptions.length === 0 && <li className="text-slate-500">None on file; all ship-to states are taxed.</li>}
            {data.taxExemptions.map((t) => (
              <li key={t.certificate} className="flex flex-wrap items-center gap-2">
                <span className="font-mono">{t.state}</span> {t.certificate} <span className="text-xs text-slate-500">({t.type})</span>
                {t.valid ? <Badge tone="green">valid to {date(t.expiresOn)}</Badge> : <Badge tone="red">expired {date(t.expiresOn)}</Badge>}
              </li>
            ))}
          </ul>
        </Card>

        <Card className="overflow-x-auto">
          <H2>Contract price book</H2>
          {data.contractPrices.length === 0 ? (
            <p className="text-sm text-slate-500">No negotiated SKU prices; this account pays list (less any volume breaks).</p>
          ) : (
            <table className="w-full text-sm">
              <tbody>
                {data.contractPrices.map((c) => {
                  const expired = new Date(c.validTo) < new Date()
                  return (
                    <tr key={c.sku} className="border-t border-slate-100 dark:border-slate-800">
                      <td className="py-1.5">
                        <SkuLink sku={c.sku} /> <span className="text-xs text-slate-500">{c.ref}</span>
                        {expired && (
                          <span className="ml-1">
                            <Badge tone="red">expired</Badge>
                          </span>
                        )}
                      </td>
                      <td className="py-1.5 text-right text-xs text-slate-500 line-through tabular-nums">{money(c.listPrice)}</td>
                      <td className="py-1.5 text-right font-semibold tabular-nums">{money(c.price)}</td>
                    </tr>
                  )
                })}
              </tbody>
            </table>
          )}
        </Card>

        <Card>
          <H2>Category discount matrix</H2>
          {data.categoryDiscounts.length === 0 ? (
            <p className="text-sm text-slate-500">No category discounts.</p>
          ) : (
            <div className="space-y-2">
              {data.categoryDiscounts.map((d) => (
                <div key={d.categoryId} className="text-sm">
                  <div className="flex justify-between">
                    <span>{d.category}</span>
                    <span className="font-semibold">{d.pct}% off list</span>
                  </div>
                  <Meter value={d.pct} max={50} warnAt={2} />
                </div>
              ))}
            </div>
          )}
        </Card>
      </div>
    </div>
  )
}
