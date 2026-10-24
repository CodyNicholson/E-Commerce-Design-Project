const usd = new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD' })
const usd0 = new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD', maximumFractionDigits: 0 })

export const money = (v: number) => usd.format(v)
export const money0 = (v: number) => usd0.format(v)

export function dateTime(s: string) {
  return new Date(s).toLocaleString(undefined, { month: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit' })
}

export function date(s: string) {
  return new Date(s).toLocaleDateString(undefined, { year: 'numeric', month: 'short', day: 'numeric', timeZone: 'UTC' })
}

export const methodLabel: Record<string, string> = {
  will_call: 'Will-call pickup',
  courier: 'Same-day courier',
  ground: 'Ground',
  hazmat_ground: 'Hazmat ground',
  drop_ship: 'Supplier drop-ship',
  backorder: 'Backorder',
}

export const nodeKindLabel: Record<string, string> = {
  dc: 'Distribution center',
  branch: 'Branch',
  supplier: 'Supplier',
}

export const statusLabel: Record<string, string> = {
  pending_approval: 'Pending approval',
  released: 'Released',
  shipped: 'Shipped',
  rejected: 'Rejected',
}

export const sourceLabel: Record<string, string> = {
  web: 'Web',
  punchout: 'PunchOut',
  edi: 'EDI 850',
  keepstock: 'KeepStock',
}

export function attrValue(v: unknown, unit?: string) {
  if (v === undefined || v === null || v === '') return '—'
  if (typeof v === 'boolean') return v ? 'Yes' : 'No'
  return unit ? `${v} ${unit}` : String(v)
}
