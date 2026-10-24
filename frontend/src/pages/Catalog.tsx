import { useState, type FormEvent } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { Badge, Button, Callout, Card, ErrorBox, Loading, PageTitle, PriceTag, SkuLink } from '../components/ui'
import { attrValue } from '../format'
import { useApp, useFetch } from '../state'
import type { CategoryNode, SearchItem, SearchResponse } from '../types'

function CategoryTree({ nodes, active, onPick, depth = 0 }: { nodes: CategoryNode[]; active: string; onPick: (id: string) => void; depth?: number }) {
  return (
    <ul className={depth ? 'ml-3 border-l border-slate-200 pl-2 dark:border-slate-800' : ''}>
      {nodes.map((n) => (
        <li key={n.id}>
          <button
            onClick={() => onPick(n.id)}
            className={`flex w-full justify-between rounded px-2 py-1 text-left text-sm ${
              active === n.id ? 'bg-brand-600 text-white' : 'hover:bg-slate-100 dark:hover:bg-slate-800'
            }`}
          >
            <span>{n.name}</span>
            <span className="text-xs opacity-70">{n.count}</span>
          </button>
          {n.children.length > 0 && <CategoryTree nodes={n.children} active={active} onPick={onPick} depth={depth + 1} />}
        </li>
      ))}
    </ul>
  )
}

function AddButton({ item }: { item: SearchItem }) {
  const { addToCart } = useApp()
  const [added, setAdded] = useState(false)
  return (
    <Button
      variant="secondary"
      className="px-2 py-1 text-xs"
      onClick={() => {
        addToCart(item.sku, 1)
        setAdded(true)
        setTimeout(() => setAdded(false), 1200)
      }}
    >
      {added ? 'Added ✓' : 'Add'}
    </Button>
  )
}

export default function Catalog() {
  const [params, setParams] = useSearchParams()
  const q = params.get('q') ?? ''
  const category = params.get('category') ?? ''
  const [draft, setDraft] = useState(q)
  const tree = useFetch<CategoryNode[]>('/api/categories')
  const search = useFetch<SearchResponse>(`/api/products?${params.toString()}`)

  const update = (fn: (p: URLSearchParams) => void) => {
    const next = new URLSearchParams(params)
    fn(next)
    setParams(next)
  }
  const pickCategory = (id: string) =>
    update((p) => {
      ;[...p.keys()].filter((k) => k.startsWith('f.')).forEach((k) => p.delete(k))
      if (id === category) p.delete('category')
      else p.set('category', id)
    })
  const toggleFacet = (key: string, value: string) =>
    update((p) => {
      const k = `f.${key}`
      const vals = p.getAll(k)
      p.delete(k)
      const next = vals.includes(value) ? vals.filter((v) => v !== value) : [...vals, value]
      next.forEach((v) => p.append(k, v))
    })
  const submit = (e: FormEvent) => {
    e.preventDefault()
    update((p) => (draft.trim() ? p.set('q', draft.trim()) : p.delete('q')))
  }

  const data = search.data
  const facetKeys = new Set(data?.facets.map((f) => f.key))
  const anyFilter = [...params.keys()].some((k) => k.startsWith('f.'))

  return (
    <div>
      <PageTitle
        title="Technical catalog"
        subtitle="Search by keyword, our SKU, a manufacturer part number, or a competitor/OEM/obsolete number. Pick a leaf category to filter by its own technical schema."
      />
      <form onSubmit={submit} className="mb-4 flex gap-2">
        <input
          value={draft}
          onChange={(e) => setDraft(e.target.value)}
          placeholder='Try "143T", "6205LLU", "M3546T", "91257A626" or "nitrile"'
          className="flex-1"
        />
        <Button type="submit">Search</Button>
        {(q || category || anyFilter) && (
          <Button
            type="button"
            variant="secondary"
            onClick={() => {
              setDraft('')
              setParams(new URLSearchParams())
            }}
          >
            Clear
          </Button>
        )}
      </form>

      <div className="grid gap-4 lg:grid-cols-[16rem_1fr]">
        <aside className="space-y-4">
          <Card className="p-2">
            <div className="px-2 pb-1 text-xs font-semibold tracking-wide text-slate-500 uppercase">Categories</div>
            {tree.data ? <CategoryTree nodes={tree.data} active={category} onPick={pickCategory} /> : <Loading />}
          </Card>
          {data && data.facets.length > 0 && (
            <Card className="space-y-4">
              {data.facets.map((f) => (
                <div key={f.key}>
                  <div className="mb-1 text-sm font-semibold">
                    {f.label}
                    {f.unit && <span className="ml-1 font-normal text-slate-500">({f.unit})</span>}
                  </div>
                  <div className="space-y-0.5">
                    {f.values.map((v) => (
                      <label key={v.value} className="flex cursor-pointer items-center justify-between gap-2 text-sm">
                        <span className="flex items-center gap-2">
                          <input type="checkbox" checked={v.selected} onChange={() => toggleFacet(f.key, v.value)} className="h-3.5 w-3.5 p-0" />
                          {f.type === 'bool' ? (v.value === 'true' ? 'Yes' : 'No') : v.value}
                        </span>
                        <span className="text-xs text-slate-500">{v.count}</span>
                      </label>
                    ))}
                  </div>
                </div>
              ))}
            </Card>
          )}
          {data && !category && data.categoryFacets.length > 0 && (
            <Card>
              <div className="mb-1 text-sm font-semibold">Matches by category</div>
              {data.categoryFacets.map((c) => (
                <button key={c.id} onClick={() => pickCategory(c.id)} className="flex w-full justify-between py-0.5 text-left text-sm hover:text-brand-600">
                  <span>{c.name}</span>
                  <span className="text-xs text-slate-500">{c.count}</span>
                </button>
              ))}
              <p className="mt-2 text-xs text-slate-500">Pick a category to unlock its technical filters.</p>
            </Card>
          )}
        </aside>

        <div className="min-w-0 space-y-3">
          <ErrorBox error={search.error} />
          {data && data.crossRefs.length > 0 && (
            <Callout title="Part number resolved">
              {data.crossRefs.map((x, i) => (
                <div key={i} className="flex flex-wrap items-center gap-2 py-0.5">
                  <Badge tone={x.refType === 'obsolete' ? 'amber' : x.refType === 'competitor' ? 'red' : 'blue'}>{x.refType}</Badge>
                  <span>
                    {x.refBrand} <span className="font-mono">{x.refPartNumber}</span> →
                  </span>
                  <SkuLink sku={x.sku} />
                  <span className="text-slate-600 dark:text-slate-400">
                    {x.brand} {x.name}
                  </span>
                  <span className="text-xs text-slate-500">({x.note})</span>
                </div>
              ))}
            </Callout>
          )}
          {search.loading && !data && <Loading />}
          {data && (
            <Card className="overflow-x-auto p-0">
              <div className="flex items-center justify-between border-b border-slate-200 px-4 py-2 text-sm dark:border-slate-800">
                <span>
                  <strong>{data.total}</strong> products{data.query && <> matching “{data.query}”</>}
                </span>
                <span className="text-xs text-slate-500">Prices shown are your account's net price for qty 1</span>
              </div>
              {data.items.length === 0 ? (
                <div className="p-6 text-center text-sm text-slate-500">No products match. {data.crossRefs.length > 0 && 'See the resolved part above.'}</div>
              ) : (
                <table className="w-full min-w-[44rem] text-sm">
                  <thead className="bg-slate-50 text-left text-xs text-slate-500 uppercase dark:bg-slate-800/50">
                    <tr>
                      <th className="px-4 py-2">Item</th>
                      <th className="px-2 py-2">Key specs</th>
                      <th className="px-2 py-2">Availability</th>
                      <th className="px-2 py-2 text-right">Your price</th>
                      <th className="px-4 py-2" />
                    </tr>
                  </thead>
                  <tbody>
                    {data.items.map((it) => (
                      <tr key={it.sku} className="border-t border-slate-100 align-top dark:border-slate-800">
                        <td className="px-4 py-2.5">
                          <Link to={`/product/${it.sku}`} className="font-medium hover:text-brand-600">
                            {it.name}
                          </Link>
                          <div className="mt-0.5 flex flex-wrap items-center gap-1.5 text-xs text-slate-500">
                            <span className="font-mono">{it.sku}</span>· {it.brand} {it.mfrPartNumber}
                            {it.hazmatUn && <Badge tone="red">Hazmat {it.hazmatUn}</Badge>}
                            {it.dropShipOnly && <Badge tone="violet">Drop-ship</Badge>}
                          </div>
                        </td>
                        <td className="max-w-[16rem] px-2 py-2.5 text-xs text-slate-600 dark:text-slate-400">
                          {Object.entries(it.attributes)
                            .filter(([k]) => !category || facetKeys.has(k))
                            .slice(0, 4)
                            .map(([k, v]) => (
                              <div key={k}>
                                <span className="text-slate-400">{k.replaceAll('_', ' ')}:</span> {attrValue(v)}
                              </div>
                            ))}
                        </td>
                        <td className="px-2 py-2.5 text-xs whitespace-nowrap">
                          <div className={it.availability.localBranch > 0 ? 'font-medium text-emerald-700 dark:text-emerald-400' : 'text-slate-500'}>
                            {it.availability.localBranch > 0 ? `${it.availability.localBranch} at ${it.availability.branchName}` : 'Not at local branch'}
                          </div>
                          <div className="text-slate-500">{it.availability.network} in network</div>
                        </td>
                        <td className="px-2 py-2.5 text-right">
                          <PriceTag price={it.price} compact />
                          <div className="text-xs text-slate-500">/{it.uom}</div>
                        </td>
                        <td className="px-4 py-2.5 text-right">
                          <AddButton item={it} />
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              )}
            </Card>
          )}
        </div>
      </div>
    </div>
  )
}
