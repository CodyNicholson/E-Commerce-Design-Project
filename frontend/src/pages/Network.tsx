import { Badge, Card, ErrorBox, H2, Loading, PageTitle } from '../components/ui'
import { nodeKindLabel } from '../format'
import { useFetch } from '../state'
import type { NodeInfo } from '../types'

interface NetworkData {
  facility: { id: string; name: string; state: string; lat: number; lon: number }
  nodes: (NodeInfo & { city: string; state: string; skus: number; units: number; reserved: number; distanceMi: number })[]
  courierRadiusMi: number
}

const W = 720
const H = 380
const PAD = 40

// NetworkMap plots nodes on a simple equirectangular projection around the buyer's facility.
function NetworkMap({ data }: { data: NetworkData }) {
  const pts = [...data.nodes.map((n) => [n.lat, n.lon]), [data.facility.lat, data.facility.lon]]
  const lats = pts.map((p) => p[0])
  const lons = pts.map((p) => p[1])
  const [minLat, maxLat, minLon, maxLon] = [Math.min(...lats), Math.max(...lats), Math.min(...lons), Math.max(...lons)]
  const kx = (W - 2 * PAD) / (maxLon - minLon || 1)
  const ky = (H - 2 * PAD) / (maxLat - minLat || 1)
  const k = Math.min(kx, ky * 1.25)
  const x = (lon: number) => PAD + (lon - minLon) * k
  const y = (lat: number) => H - PAD - (lat - minLat) * (k / 1.25)
  // One degree of latitude is ~69 miles; the courier radius in projected pixels.
  const radius = (data.courierRadiusMi / 69) * (k / 1.25)
  const fx = x(data.facility.lon)
  const fy = y(data.facility.lat)

  return (
    <svg viewBox={`0 0 ${W} ${H}`} className="h-auto w-full" role="img" aria-label="Fulfillment network map">
      <circle cx={fx} cy={fy} r={radius} className="fill-emerald-500/10 stroke-emerald-500" strokeDasharray="4 4" />
      {data.nodes.map((n) => (
        <line key={`l-${n.id}`} x1={fx} y1={fy} x2={x(n.lon)} y2={y(n.lat)} className="stroke-slate-300 dark:stroke-slate-700" strokeWidth={1} />
      ))}
      {data.nodes.map((n) => {
        const cx = x(n.lon)
        const cy = y(n.lat)
        // Put the label on the left when a neighbour sits just to the right, so close pairs stay legible.
        const crowded = data.nodes.some((o) => o.id !== n.id && Math.abs(y(o.lat) - cy) < 14 && x(o.lon) > cx && x(o.lon) - cx < 90)
        const cls = n.kind === 'dc' ? 'fill-sky-600' : n.kind === 'branch' ? 'fill-emerald-600' : 'fill-violet-600'
        return (
          <g key={n.id}>
            {n.kind === 'dc' && <rect x={cx - 7} y={cy - 7} width={14} height={14} className={cls} />}
            {n.kind === 'branch' && <circle cx={cx} cy={cy} r={6} className={cls} />}
            {n.kind === 'supplier' && <polygon points={`${cx},${cy - 8} ${cx + 8},${cy} ${cx},${cy + 8} ${cx - 8},${cy}`} className={cls} />}
            {n.hazmatCapable && <circle cx={cx} cy={cy} r={11} fill="none" className="stroke-red-500" strokeWidth={1.5} />}
            <text x={crowded ? cx - 12 : cx + 12} y={cy + 4} textAnchor={crowded ? 'end' : 'start'} className="fill-slate-700 text-[11px] dark:fill-slate-300">
              {n.city}
            </text>
          </g>
        )
      })}
      <polygon
        points={Array.from({ length: 10 }, (_, i) => {
          const r = i % 2 ? 4 : 10
          const a = (Math.PI / 5) * i - Math.PI / 2
          return `${fx + r * Math.cos(a)},${fy + r * Math.sin(a)}`
        }).join(' ')}
        className="fill-brand-600"
      />
      <text x={fx} y={fy - 16} textAnchor="middle" className="fill-brand-700 text-[12px] font-semibold dark:fill-amber-400">
        {data.facility.name}
      </text>
    </svg>
  )
}

export default function Network() {
  const { data, error } = useFetch<NetworkData>('/api/network')
  if (error) return <ErrorBox error={error} />
  if (!data) return <Loading />
  return (
    <div className="space-y-4">
      <PageTitle
        title="Fulfillment network"
        subtitle="Inventory is spread across regional DCs, local branches and supplier drop-ship sites. The OMS computes available-to-promise per node and splits each order across them."
      />
      <div className="grid gap-4 lg:grid-cols-[1fr_22rem]">
        <Card>
          <H2 aside={`as seen from ${data.facility.name}`}>Network map</H2>
          <NetworkMap data={data} />
          <div className="mt-2 flex flex-wrap gap-3 text-xs text-slate-600 dark:text-slate-400">
            <span>■ DC</span>
            <span>● Branch</span>
            <span>◆ Supplier</span>
            <span className="text-red-600">◯ hazmat-licensed</span>
            <span className="text-emerald-600">dashed: {data.courierRadiusMi} mi same-day courier radius</span>
          </div>
        </Card>
        <Card>
          <H2>Routing rules</H2>
          <ol className="list-decimal space-y-2 pl-5 text-sm">
            <li><strong>Will-call</strong> takes stock from the nearest branch first; it is ready in about an hour.</li>
            <li><strong>Ship</strong> uses a branch only inside the courier radius (same day). Otherwise it uses the DCs, nearest first, at one business day per 450 miles plus one.</li>
            <li><strong>Hazmat</strong> lines only come from licensed nodes and only go by ground, never by courier.</li>
            <li><strong>Drop-ship-only</strong> items (drums, large motors) only come from supplier sites.</li>
            <li>Suppliers are the last resort before <strong>backorder</strong>.</li>
            <li><strong>Ship complete</strong> picks one node that can fill every line. If none can, the lines are held so they all arrive together.</li>
          </ol>
        </Card>
      </div>
      <Card className="overflow-x-auto p-0">
        <table className="w-full min-w-[40rem] text-sm">
          <thead className="bg-slate-50 text-left text-xs text-slate-500 uppercase dark:bg-slate-800/50">
            <tr>
              <th className="px-4 py-2">Node</th>
              <th className="px-2 py-2">Type</th>
              <th className="px-2 py-2">Capabilities</th>
              <th className="px-2 py-2 text-right">Distance</th>
              <th className="px-2 py-2 text-right">SKUs in stock</th>
              <th className="px-2 py-2 text-right">On hand</th>
              <th className="px-4 py-2 text-right">Reserved</th>
            </tr>
          </thead>
          <tbody>
            {data.nodes.map((n) => (
              <tr key={n.id} className="border-t border-slate-100 dark:border-slate-800">
                <td className="px-4 py-2">
                  <div className="font-medium">{n.name}</div>
                  <div className="text-xs text-slate-500">
                    {n.city}, {n.state}
                  </div>
                </td>
                <td className="px-2 py-2">{nodeKindLabel[n.kind]}</td>
                <td className="px-2 py-2">
                  <div className="flex flex-wrap gap-1">
                    {n.willCall && <Badge tone="green">Will-call</Badge>}
                    {n.hazmatCapable && <Badge tone="red">Hazmat</Badge>}
                    {n.leadDays > 0 && <Badge tone="violet">{n.leadDays}-day lead</Badge>}
                  </div>
                </td>
                <td className="px-2 py-2 text-right tabular-nums">{n.distanceMi} mi</td>
                <td className="px-2 py-2 text-right tabular-nums">{n.skus}</td>
                <td className="px-2 py-2 text-right tabular-nums">{n.units.toLocaleString()}</td>
                <td className="px-4 py-2 text-right tabular-nums">{n.reserved}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </Card>
    </div>
  )
}
