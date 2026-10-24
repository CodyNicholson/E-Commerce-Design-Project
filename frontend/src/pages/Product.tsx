import { useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { Badge, Button, Callout, Card, ErrorBox, H2, Loading, PriceTag, PriceTrace, SkuLink } from '../components/ui'
import { attrValue, money, nodeKindLabel } from '../format'
import { useApp, useFetch } from '../state'
import type { ProductDetail } from '../types'

export default function ProductPage() {
  const { sku = '' } = useParams()
  const [qty, setQty] = useState(1)
  const { addToCart, me } = useApp()
  const { data, error } = useFetch<ProductDetail>(`/api/products/${sku}?qty=${qty}`)
  const [added, setAdded] = useState(false)

  if (error) return <ErrorBox error={error} />
  if (!data) return <Loading />
  const { product: p, price } = data
  const certMissing = data.restriction && me && !me.user.certifications.includes(data.restriction.cert)

  return (
    <div className="space-y-4">
      <nav className="text-sm text-slate-500">
        <Link to="/catalog" className="hover:underline">
          Catalog
        </Link>
        {data.breadcrumb.map((b) => (
          <span key={b.id}>
            {' › '}
            <Link to={`/catalog?category=${b.id}`} className="hover:underline">
              {b.name}
            </Link>
          </span>
        ))}
      </nav>

      {p.status === 'discontinued' && data.replacement && (
        <Callout title="This item is discontinued">
          Replaced by <SkuLink sku={data.replacement.sku} /> {data.replacement.name} at {money(data.replacement.price.unitPrice)}.
        </Callout>
      )}

      <div className="grid gap-4 lg:grid-cols-[1fr_22rem]">
        <Card>
          <div className="flex flex-wrap gap-2">
            <Badge>{data.category.name}</Badge>
            <Badge tone="slate" title="UN Standard Products and Services Code">UNSPSC {data.category.unspsc}</Badge>
            {p.hazmatUn && <Badge tone="red">Hazmat {p.hazmatUn} · Class {p.hazmatClass}</Badge>}
            {p.dropShipOnly && <Badge tone="violet">Ships direct from manufacturer</Badge>}
          </div>
          <h1 className="mt-2 text-xl font-bold">{p.name}</h1>
          <div className="mt-1 text-sm text-slate-600 dark:text-slate-400">
            <span className="font-mono">{p.sku}</span> · {p.brand} <span className="font-mono">{p.mfrPartNumber}</span> · sold per {p.uom}
          </div>
          <p className="mt-3 text-sm">{p.description}</p>

          <h3 className="mt-5 mb-2 text-sm font-semibold">Technical specifications</h3>
          <table className="w-full text-sm">
            <tbody>
              {data.category.attributeSchema.map((a) => (
                <tr key={a.key} className="border-t border-slate-100 dark:border-slate-800">
                  <td className="w-1/2 py-1.5 text-slate-500">{a.label}</td>
                  <td className="py-1.5 font-medium">{attrValue(p.attributes[a.key], a.unit)}</td>
                </tr>
              ))}
              <tr className="border-t border-slate-100 dark:border-slate-800">
                <td className="py-1.5 text-slate-500">Shipping weight</td>
                <td className="py-1.5 font-medium">{p.weightLb} lb</td>
              </tr>
            </tbody>
          </table>
          {p.sdsUrl && (
            <p className="mt-3 text-sm text-slate-600 dark:text-slate-400">
              Safety Data Sheet on file ({p.sdsUrl}). {p.hazmatUn && 'Ships ground only from hazmat-licensed nodes.'}
            </p>
          )}
        </Card>

        <div className="space-y-4">
          <Card>
            <div className="text-xs text-slate-500">Your price · {me?.user.corporation}</div>
            <div className="mt-1 text-3xl">
              <PriceTag price={price} />
            </div>
            <div className="mt-3 flex items-center gap-2">
              <input type="number" min={1} value={qty} onChange={(e) => setQty(Math.max(1, Number(e.target.value) || 1))} className="w-24" aria-label="Quantity" />
              <Button
                className="flex-1"
                disabled={p.status !== 'active'}
                onClick={() => {
                  addToCart(p.sku, qty)
                  setAdded(true)
                  setTimeout(() => setAdded(false), 1500)
                }}
              >
                {added ? 'Added to cart ✓' : 'Add to cart'}
              </Button>
            </div>
            <div className="mt-2 text-sm">
              Extended: <strong>{money(price.extended)}</strong>
            </div>
            {price.nextTier && (
              <div className="mt-1 text-xs text-emerald-700 dark:text-emerald-400">
                Buy {price.nextTier.minQty}+ to unlock the {price.nextTier.pct}% volume tier.
              </div>
            )}
            {data.restriction && (
              <div className={`mt-3 rounded-md p-2 text-xs ${certMissing ? 'bg-red-50 text-red-800 dark:bg-red-950 dark:text-red-200' : 'bg-emerald-50 text-emerald-800 dark:bg-emerald-950 dark:text-emerald-200'}`}>
                Your company restricts {data.restriction.categoryName} to buyers holding <strong>{data.restriction.cert}</strong>.{' '}
                {certMissing ? 'You do not hold it; checkout will be blocked.' : 'You hold it.'}
              </div>
            )}
          </Card>
          <Card>
            <H2>How your price was resolved</H2>
            <PriceTrace price={price} />
            <p className="mt-2 text-xs text-slate-500">Every agreement that applies is evaluated; the lowest valid price wins.</p>
            {data.tiers && (
              <div className="mt-3 text-xs">
                <span className="font-semibold">Volume breaks:</span> {data.tiers.map((t) => `${t.minQty}+ → ${t.pct}%`).join(' · ')}
              </div>
            )}
          </Card>
        </div>
      </div>

      <div className="grid gap-4 lg:grid-cols-2">
        <Card>
          <H2 aside={`from ${data.facility.name}`}>Available to promise by node</H2>
          <table className="w-full text-sm">
            <thead className="text-left text-xs text-slate-500 uppercase">
              <tr>
                <th className="py-1">Node</th>
                <th className="py-1">Type</th>
                <th className="py-1 text-right">Distance</th>
                <th className="py-1 text-right">ATP</th>
              </tr>
            </thead>
            <tbody>
              {data.inventory.map((n) => (
                <tr key={n.id} className="border-t border-slate-100 dark:border-slate-800">
                  <td className="py-1.5">
                    {n.name}
                    {n.hazmatCapable && <span className="ml-1.5 text-[10px] font-semibold text-red-600 uppercase dark:text-red-400" title="Licensed to store and ship hazmat">hazmat</span>}
                  </td>
                  <td className="py-1.5 text-xs text-slate-500">{nodeKindLabel[n.kind]}</td>
                  <td className="py-1.5 text-right text-xs tabular-nums">{n.distanceMi} mi</td>
                  <td className={`py-1.5 text-right font-semibold tabular-nums ${n.atp > 0 ? '' : 'text-slate-400'}`}>{n.stocked ? n.atp : '—'}</td>
                </tr>
              ))}
            </tbody>
          </table>
          <p className="mt-2 text-xs text-slate-500">ATP = on hand − reserved for released orders. “—” means the node does not stock this SKU.</p>
        </Card>

        <div className="space-y-4">
          <Card>
            <H2 aside={`qty ${qty}`}>Same SKU, different customers</H2>
            <table className="w-full text-sm">
              <tbody>
                {data.priceByCustomer.map((c) => (
                  <tr key={c.corporationId} className="border-t border-slate-100 dark:border-slate-800">
                    <td className="py-1.5">{c.corporation}</td>
                    <td className="py-1.5 text-xs text-slate-500">{c.price.label}</td>
                    <td className="py-1.5 text-right font-semibold tabular-nums">{money(c.price.unitPrice)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </Card>
          {data.crossRefs.length > 0 && (
            <Card>
              <H2>Cross-references to this SKU</H2>
              <ul className="space-y-1 text-sm">
                {data.crossRefs.map((x, i) => (
                  <li key={i} className="flex flex-wrap items-center gap-2">
                    <Badge tone={x.refType === 'obsolete' ? 'amber' : x.refType === 'competitor' ? 'red' : 'blue'}>{x.refType}</Badge>
                    {x.refBrand} <span className="font-mono">{x.refPartNumber}</span>
                    <span className="text-xs text-slate-500">{x.note}</span>
                  </li>
                ))}
              </ul>
            </Card>
          )}
          {data.equivalents.length > 0 && (
            <Card>
              <H2>Equivalent items (identical specs)</H2>
              {data.equivalents.map((e) => (
                <div key={e.sku} className="flex items-center justify-between gap-2 border-t border-slate-100 py-1.5 text-sm dark:border-slate-800">
                  <span>
                    <SkuLink sku={e.sku} /> {e.brand}
                  </span>
                  <span className="font-semibold tabular-nums">{money(e.price.unitPrice)}</span>
                </div>
              ))}
            </Card>
          )}
        </div>
      </div>
    </div>
  )
}
