import type { ButtonHTMLAttributes, ReactNode } from 'react'
import { Link } from 'react-router-dom'
import { methodLabel, money } from '../format'
import type { Check, Plan, PriceResult } from '../types'

export function Card({ children, className = '' }: { children: ReactNode; className?: string }) {
  return (
    <section className={`rounded-xl border border-slate-200 bg-white p-4 shadow-sm dark:border-slate-800 dark:bg-slate-900 ${className}`}>
      {children}
    </section>
  )
}

export function H2({ children, aside }: { children: ReactNode; aside?: ReactNode }) {
  return (
    <div className="mb-3 flex flex-wrap items-baseline justify-between gap-2">
      <h2 className="text-base font-semibold">{children}</h2>
      {aside && <div className="text-sm text-slate-500 dark:text-slate-400">{aside}</div>}
    </div>
  )
}

export function PageTitle({ title, subtitle, children }: { title: string; subtitle?: ReactNode; children?: ReactNode }) {
  return (
    <div className="mb-5 flex flex-wrap items-end justify-between gap-3">
      <div>
        <h1 className="text-2xl font-bold tracking-tight">{title}</h1>
        {subtitle && <p className="mt-1 max-w-3xl text-sm text-slate-600 dark:text-slate-400">{subtitle}</p>}
      </div>
      {children}
    </div>
  )
}

type Tone = 'slate' | 'green' | 'amber' | 'red' | 'blue' | 'violet'
const tones: Record<Tone, string> = {
  slate: 'bg-slate-100 text-slate-700 dark:bg-slate-800 dark:text-slate-300',
  green: 'bg-emerald-100 text-emerald-800 dark:bg-emerald-900/50 dark:text-emerald-300',
  amber: 'bg-amber-100 text-amber-800 dark:bg-amber-900/50 dark:text-amber-300',
  red: 'bg-red-100 text-red-800 dark:bg-red-900/50 dark:text-red-300',
  blue: 'bg-sky-100 text-sky-800 dark:bg-sky-900/50 dark:text-sky-300',
  violet: 'bg-violet-100 text-violet-800 dark:bg-violet-900/50 dark:text-violet-300',
}

export function Badge({ children, tone = 'slate', title }: { children: ReactNode; tone?: Tone; title?: string }) {
  return (
    <span title={title} className={`inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-xs font-medium whitespace-nowrap ${tones[tone]}`}>
      {children}
    </span>
  )
}

export function StatusBadge({ status }: { status: string }) {
  const tone: Record<string, Tone> = { pending_approval: 'amber', released: 'blue', shipped: 'green', rejected: 'red' }
  const label: Record<string, string> = { pending_approval: 'Pending approval', released: 'Released', shipped: 'Shipped', rejected: 'Rejected' }
  return <Badge tone={tone[status] ?? 'slate'}>{label[status] ?? status}</Badge>
}

type Variant = 'primary' | 'secondary' | 'danger' | 'ghost'
const variants: Record<Variant, string> = {
  primary: 'bg-brand-600 text-white hover:bg-brand-700 disabled:bg-slate-400',
  secondary: 'border border-slate-300 bg-white text-slate-800 hover:bg-slate-50 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-100 dark:hover:bg-slate-700',
  danger: 'bg-red-600 text-white hover:bg-red-700',
  ghost: 'text-brand-600 hover:bg-brand-50 dark:text-amber-400 dark:hover:bg-slate-800',
}

export function Button({ variant = 'primary', className = '', ...rest }: ButtonHTMLAttributes<HTMLButtonElement> & { variant?: Variant }) {
  return (
    <button
      className={`inline-flex items-center justify-center gap-1.5 rounded-md px-3 py-1.5 text-sm font-medium transition disabled:cursor-not-allowed disabled:opacity-60 ${variants[variant]} ${className}`}
      {...rest}
    />
  )
}

export function Stat({ label, value, hint }: { label: string; value: ReactNode; hint?: ReactNode }) {
  return (
    <div className="rounded-lg border border-slate-200 bg-white p-3 dark:border-slate-800 dark:bg-slate-900">
      <div className="text-xs font-medium tracking-wide text-slate-500 uppercase dark:text-slate-400">{label}</div>
      <div className="mt-1 text-lg font-semibold tabular-nums">{value}</div>
      {hint && <div className="mt-0.5 text-xs text-slate-500 dark:text-slate-400">{hint}</div>}
    </div>
  )
}

export function Meter({ value, max, warnAt = 0.8 }: { value: number; max: number; warnAt?: number }) {
  const pct = max > 0 ? Math.min(value / max, 1) : 0
  const color = pct >= 1 ? 'bg-red-500' : pct >= warnAt ? 'bg-amber-500' : 'bg-emerald-500'
  return (
    <div className="h-2 w-full overflow-hidden rounded-full bg-slate-200 dark:bg-slate-800">
      <div className={`h-full ${color}`} style={{ width: `${pct * 100}%` }} />
    </div>
  )
}

export function Loading() {
  return <div className="py-8 text-center text-sm text-slate-500">Loading…</div>
}

export function ErrorBox({ error }: { error: string | null }) {
  if (!error) return null
  return <div className="rounded-lg border border-red-300 bg-red-50 p-3 text-sm text-red-800 dark:border-red-800 dark:bg-red-950 dark:text-red-200">{error}</div>
}

export function Callout({ title, children, tone = 'brand' }: { title?: string; children: ReactNode; tone?: 'brand' | 'blue' }) {
  const cls =
    tone === 'blue'
      ? 'border-sky-300 bg-sky-50 dark:border-sky-800 dark:bg-sky-950/50'
      : 'border-brand-500/40 bg-brand-50 dark:border-amber-800 dark:bg-amber-950/30'
  return (
    <div className={`rounded-lg border p-3 text-sm ${cls}`}>
      {title && <div className="mb-1 font-semibold">{title}</div>}
      <div className="text-slate-700 dark:text-slate-300">{children}</div>
    </div>
  )
}

export function SkuLink({ sku }: { sku: string }) {
  return (
    <Link to={`/product/${sku}`} className="font-mono text-brand-600 hover:underline dark:text-amber-400">
      {sku}
    </Link>
  )
}

const sourceTone: Record<string, Tone> = { list: 'slate', contract: 'violet', category: 'blue', volume: 'green' }

export function PriceTag({ price, compact = false }: { price: PriceResult; compact?: boolean }) {
  const discounted = price.unitPrice < price.listPrice
  return (
    <div className={compact ? 'text-right' : ''}>
      <div className="font-semibold tabular-nums">{money(price.unitPrice)}</div>
      {discounted && (
        <div className="text-xs whitespace-nowrap text-slate-500 dark:text-slate-400">
          <span className="line-through">{money(price.listPrice)}</span> · {price.savingsPct}% off
        </div>
      )}
      {!compact && (
        <div className="mt-1">
          <Badge tone={sourceTone[price.source]}>{price.label}</Badge>
        </div>
      )}
    </div>
  )
}

// PriceTrace shows every price candidate the resolver considered and which one won.
export function PriceTrace({ price }: { price: PriceResult }) {
  return (
    <table className="w-full text-sm">
      <tbody>
        {price.candidates.map((c) => {
          const win = c.applies && c.label === price.label
          return (
            <tr key={c.label} className={`border-t border-slate-100 dark:border-slate-800 ${win ? 'font-semibold' : ''}`}>
              <td className="py-1.5 pr-2">
                <Badge tone={sourceTone[c.source]}>{c.source}</Badge>
              </td>
              <td className={`py-1.5 pr-2 ${c.applies ? '' : 'text-slate-400 line-through'}`}>
                {c.label}
                {c.reason && <span className="ml-1 text-xs no-underline">({c.reason})</span>}
              </td>
              <td className="py-1.5 text-right tabular-nums">{money(c.unitPrice)}</td>
              <td className="w-6 py-1.5 text-right">{win ? '✓' : ''}</td>
            </tr>
          )
        })}
      </tbody>
    </table>
  )
}

const checkStyle: Record<Check['level'], { icon: string; cls: string }> = {
  pass: { icon: '✓', cls: 'text-emerald-600 dark:text-emerald-400' },
  info: { icon: 'i', cls: 'text-sky-600 dark:text-sky-400' },
  approval: { icon: '!', cls: 'text-amber-600 dark:text-amber-400' },
  block: { icon: '✕', cls: 'text-red-600 dark:text-red-400' },
}

export function Checks({ checks }: { checks: Check[] }) {
  return (
    <ul className="space-y-1.5 text-sm">
      {checks.map((c, i) => (
        <li key={i} className="flex gap-2">
          <span className={`mt-0.5 inline-flex h-4 w-4 shrink-0 items-center justify-center rounded-full border text-[10px] font-bold ${checkStyle[c.level].cls} border-current`}>
            {checkStyle[c.level].icon}
          </span>
          <span>
            <span className="font-mono text-xs text-slate-500 dark:text-slate-400">{c.code}</span> {c.message}
          </span>
        </li>
      ))}
    </ul>
  )
}

const methodTone: Record<string, Tone> = { will_call: 'green', courier: 'green', ground: 'blue', hazmat_ground: 'red', drop_ship: 'violet', backorder: 'amber' }

export function PlanView({ plan }: { plan: Plan }) {
  return (
    <div className="space-y-2">
      {plan.shipments.map((s, i) => (
        <div key={i} className="rounded-lg border border-slate-200 p-3 dark:border-slate-800">
          <div className="flex flex-wrap items-center justify-between gap-2">
            <div className="flex items-center gap-2">
              <span className="text-xs font-semibold text-slate-500">#{i + 1}</span>
              <span className="font-medium">{s.nodeName}</span>
              <span className="text-xs text-slate-500">{s.distanceMi} mi</span>
            </div>
            <Badge tone={methodTone[s.method]}>{methodLabel[s.method] ?? s.method}</Badge>
          </div>
          <div className="mt-1 text-sm text-slate-600 dark:text-slate-400">
            {s.etaLabel}
            {s.note && <span className="ml-1 text-amber-700 dark:text-amber-400">· {s.note}</span>}
          </div>
          <div className="mt-1 flex flex-wrap gap-1.5">
            {s.lines.map((l) => (
              <span key={l.sku} className="rounded bg-slate-100 px-1.5 py-0.5 font-mono text-xs dark:bg-slate-800">
                {l.qty} × {l.sku}
              </span>
            ))}
          </div>
        </div>
      ))}
      {plan.backorders.length > 0 && (
        <div className="rounded-lg border border-amber-300 bg-amber-50 p-3 text-sm dark:border-amber-800 dark:bg-amber-950/40">
          <span className="font-medium">Backordered</span> (~{plan.backorderEtaDays} business days):{' '}
          {plan.backorders.map((b) => `${b.qty} × ${b.sku}`).join(', ')}
        </div>
      )}
      {plan.notes.length > 0 && (
        <ul className="list-disc pl-5 text-xs text-slate-500 dark:text-slate-400">
          {plan.notes.map((n) => (
            <li key={n}>{n}</li>
          ))}
        </ul>
      )}
    </div>
  )
}

export function Field({ label, children, hint }: { label: string; children: ReactNode; hint?: ReactNode }) {
  return (
    <label className="block text-sm">
      <span className="mb-1 block font-medium text-slate-700 dark:text-slate-300">{label}</span>
      {children}
      {hint && <span className="mt-1 block text-xs text-slate-500 dark:text-slate-400">{hint}</span>}
    </label>
  )
}
