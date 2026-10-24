import { Link } from 'react-router-dom'
import { Card, PageTitle } from '../components/ui'
import { useApp } from '../state'

interface Scenario {
  persona: string
  personaId: string
  steps: string
  to: string
}

interface Intricacy {
  n: number
  title: string
  why: string
  module: string
  to: string
  scenarios: Scenario[]
}

const intricacies: Intricacy[] = [
  {
    n: 1,
    title: 'High-dimensional technical catalog & cross-referencing',
    why: 'Every leaf category carries its own technical schema (NEMA frame and enclosure for motors, thread pitch and grade for fasteners, ANSI cut level for gloves, UN numbers and SDS for chemicals). Buyers search by spec, or paste a competitor or obsolete part number and expect the exact replacement.',
    module: 'PIM: categories with JSON attribute schemas, disjunctive facets, cross-reference table',
    to: '/catalog',
    scenarios: [
      { persona: 'Dana', personaId: 'u-dana', steps: 'Catalog → Motors → filter 1 HP + 143T + TEFC. Note each facet still shows counts for its siblings.', to: '/catalog?category=motors-ac' },
      { persona: 'Dana', personaId: 'u-dana', steps: 'Search the NTN bearing number 6205LLU, then the obsolete motor 2N864.', to: '/catalog?q=6205LLU' },
    ],
  },
  {
    n: 2,
    title: 'Hierarchical corporate accounts & purchasing governance',
    why: 'The customer is Corporation → Division → Facility → Cost Center → Buyer. Orders are checked against per-order and monthly limits, certification-gated categories, blanket PO balances and state tax-exemption certificates, then routed up an approval chain.',
    module: 'Accounts + governance engine: limits, restrictions, blanket POs, escalation',
    to: '/account',
    scenarios: [
      { persona: 'Dana', personaId: 'u-dana', steps: 'Add 15 × 4KW72 to the cart and preview: it goes to Marcus, and because it is above his $10k authority it escalates to Ruth.', to: '/product/4KW72' },
      { persona: 'Dana', personaId: 'u-dana', steps: 'Try the 30A stainless disconnect 2YDW3: blocked, Dana is not a licensed electrician. Switch to Luis and it passes.', to: '/product/2YDW3' },
      { persona: 'Priya', personaId: 'u-priya', steps: 'Check out in Wichita: the KS exemption certificate has expired (tax applies) and the 2025 blanket PO is closed.', to: '/cart' },
    ],
  },
  {
    n: 3,
    title: 'Dynamic contract pricing & eProcurement (PunchOut / EDI)',
    why: 'Two buyers see different prices for the same SKU: contract price books, category discount matrices and volume tiers compete and the best price wins. Large customers shop from inside SAP Ariba or Coupa via cXML PunchOut, then send the real order as an EDI 850.',
    module: 'Pricing resolver with full trace; cXML setup/order message; X12 850 in, 855 out',
    to: '/eprocurement',
    scenarios: [
      { persona: 'Sam', personaId: 'u-sam', steps: 'Open bearing 6BRG1 and compare "Price by customer": Atlas has a contract, MFP has a category discount, Lakeshore pays list.', to: '/product/6BRG1' },
      { persona: 'Any', personaId: '', steps: 'Run a Coupa PunchOut, transfer the cart, then submit the generated EDI 850 and read the 855 acknowledgment.', to: '/eprocurement' },
    ],
  },
  {
    n: 4,
    title: 'Multi-node available-to-promise & split-order routing',
    why: 'Stock sits in regional DCs, local branches (1-hour will-call, same-day courier) and supplier drop-ship sites. The OMS computes ATP per node and splits lines while honouring hazmat rules (licensed nodes, ground only), drop-ship-only items and ship-complete vs. ship-available preferences.',
    module: 'OMS: ATP = on hand − reserved, sourcing planner, reservations, shipments',
    to: '/network',
    scenarios: [
      { persona: 'Dana', personaId: 'u-dana', steps: 'Cart 3 × 4KW71 + 4 × 2LUB8 with will-call: 2 motors at the Milwaukee branch, 1 from Minooka, aerosols ground-only from a hazmat DC.', to: '/cart' },
      { persona: 'Dana', personaId: 'u-dana', steps: 'Cart 8 × washdown motor 4KW80: 1 from Minooka, 4 from Kansas City, 3 drop-shipped by the manufacturer. Toggle ship-complete to see the order held for consolidation.', to: '/product/4KW80' },
    ],
  },
  {
    n: 5,
    title: 'KeepStock vendor-managed inventory & onsite IoT',
    why: 'The distributor lives inside the plant: barcode bins with min/max levels, badge-swipe vending with per-person daily limits. Counting a bin below min or vending below par raises a replenishment order automatically, which is what creates the switching cost.',
    module: 'KeepStock: bins, vending slots, events, auto-replenishment into the OMS',
    to: '/keepstock',
    scenarios: [
      { persona: 'Dana', personaId: 'u-dana', steps: 'Scan bin BIN-MKE-003 with a count of 2: a replenishment order is raised to max. Ship it from the order page and the bin refills.', to: '/keepstock' },
      { persona: 'Dana', personaId: 'u-dana', steps: 'Swipe badge B-1001 (Tom) at slot C1 twice: the second swipe hits the daily limit.', to: '/keepstock' },
    ],
  },
]

export default function Home() {
  const { setUserId, punchout } = useApp()
  return (
    <div>
      <PageTitle
        title="How an industrial MRO distributor really works"
        subtitle="A working mirror of an enterprise B2B MRO platform. When a conveyor motor fails at 2:00 AM, the plant engineer searches by spec, checks branch stock for pickup, charges a cost center and routes the purchase through governance. Each module below is a real service you can exercise."
      />
      <div className="grid gap-4">
        {intricacies.map((it) => (
          <Card key={it.n}>
            <div className="flex flex-col gap-4 md:flex-row">
              <div className="md:w-1/2">
                <div className="flex items-center gap-2">
                  <span className="flex h-7 w-7 shrink-0 items-center justify-center rounded-full bg-brand-600 text-sm font-bold text-white">{it.n}</span>
                  <h2 className="font-semibold">{it.title}</h2>
                </div>
                <p className="mt-2 text-sm text-slate-600 dark:text-slate-400">{it.why}</p>
                <p className="mt-2 text-xs text-slate-500">
                  <span className="font-semibold">Implemented by:</span> {it.module}
                </p>
                <Link to={it.to} className="mt-2 inline-block text-sm font-medium text-brand-600 hover:underline dark:text-amber-400">
                  Open module →
                </Link>
              </div>
              <div className="space-y-2 md:w-1/2">
                <div className="text-xs font-semibold tracking-wide text-slate-500 uppercase">Try it</div>
                {it.scenarios.map((s, i) => (
                  <Link
                    key={i}
                    to={s.to}
                    onClick={() => s.personaId && !punchout && setUserId(s.personaId)}
                    className="block rounded-lg border border-slate-200 p-3 text-sm hover:border-brand-500 hover:bg-brand-50/50 dark:border-slate-800 dark:hover:bg-slate-800"
                  >
                    <span className="mr-1 rounded bg-slate-100 px-1.5 py-0.5 text-xs font-medium dark:bg-slate-800">as {s.persona}</span>
                    {s.steps}
                  </Link>
                ))}
              </div>
            </div>
          </Card>
        ))}
      </div>
    </div>
  )
}
