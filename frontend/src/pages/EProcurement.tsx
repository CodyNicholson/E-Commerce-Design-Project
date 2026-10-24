import { useEffect, useState } from 'react'
import { Link, useLocation, useNavigate } from 'react-router-dom'
import { api } from '../api'
import { Badge, Button, Callout, Card, ErrorBox, H2, PageTitle, PlanView } from '../components/ui'
import { dateTime, money } from '../format'
import { useFetch } from '../state'
import type { Governance, Plan } from '../types'

interface Identity {
  identity: string
  erp: string
  corporation: string
  defaultEmail: string
}

interface ErpReturn {
  erp: string
  orderMessage: string
  total: number
  edi850: string
}

interface EdiResult {
  partner: string
  orderId: string
  outcome: 'accepted' | 'rejected'
  lines: { lineNo: string; poPart: string; sku: string; qty: number; poPrice: number; ourPrice: number; status: string; note: string }[]
  ack855: string
  governance?: Governance
  plan?: Plan
  po: { poNumber: string; warnings: string[] }
}

const ackLabel: Record<string, { text: string; tone: 'green' | 'amber' | 'red' | 'blue' }> = {
  IA: { text: 'IA accepted', tone: 'green' },
  IP: { text: 'IP price change', tone: 'amber' },
  IB: { text: 'IB backordered', tone: 'blue' },
  IR: { text: 'IR rejected', tone: 'red' },
}

// A hand-written partner 850 exercising cross-references, a discontinued SKU and a price mismatch.
function sample850() {
  const pad = (s: string, n: number) => s.padEnd(n, ' ')
  const d = new Date()
  const yymmdd = d.toISOString().slice(2, 10).replaceAll('-', '')
  const ymd = d.toISOString().slice(0, 10).replaceAll('-', '')
  const segs = [
    `ISA*00*${pad('', 10)}*00*${pad('', 10)}*ZZ*${pad('ATLASAERO', 15)}*ZZ*${pad('MROMIRROR', 15)}*${yymmdd}*0900*U*00401*000000777*0*P*>`,
    `GS*PO*ATLASAERO*MROMIRROR*${ymd}*0900*777*X*004010`,
    'ST*850*0001',
    `BEG*00*SA*ATL-60021**${ymd}`,
    'REF*DP*7100',
    'N1*ST*Fort Worth Assembly*92*FTW',
    'PO1*1*20*EA*12.50**VP*6BRG1*BP*ATL-BRG-6205',
    'PO1*2*4*PK*37.52**VP*11106440*BP*ATL-HCS-0813',
    'PO1*3*1*EA*399.00**VP*2N864*BP*ATL-MTR-1HP',
    'PO1*4*2*EA*33.12**VP*9KL33',
    'CTT*4',
    'SE*10*0001',
    'GE*1*777',
    'IEA*1*000000777',
  ]
  return segs.map((s) => s + '~').join('\n')
}

export default function EProcurement() {
  const location = useLocation()
  const navigate = useNavigate()
  const identities = useFetch<Identity[]>('/api/punchout/identities')
  const [identity, setIdentity] = useState('')
  const [setupXml, setSetupXml] = useState('')
  const [setupResp, setSetupResp] = useState('')
  const [erpReturn, setErpReturn] = useState<ErpReturn | null>(null)
  const [edi, setEdi] = useState(sample850())
  const [ediResult, setEdiResult] = useState<EdiResult | null>(null)
  const [error, setError] = useState<string | null>(null)
  const log = useFetch<{ id: number; direction: string; docType: string; partner: string; orderId: string | null; createdAt: string }[]>('/api/edi/messages', [ediResult])

  useEffect(() => {
    if (location.pathname !== '/erp/return') return
    try {
      const raw = sessionStorage.getItem('mro.erpReturn')
      if (raw) {
        const r = JSON.parse(raw) as ErpReturn
        setErpReturn(r)
        if (r.edi850) setEdi(r.edi850)
      }
    } catch {
      /* ignore */
    }
  }, [location.pathname])

  useEffect(() => {
    if (!identity && identities.data?.length) setIdentity(identities.data[0].identity)
  }, [identities.data, identity])

  const loadSample = async () => {
    setError(null)
    setSetupResp('')
    try {
      setSetupXml(await api<string>(`/api/punchout/sample?identity=${encodeURIComponent(identity)}`, { asText: true }))
    } catch (e) {
      setError((e as Error).message)
    }
  }
  useEffect(() => {
    if (identity) loadSample()
  }, [identity])

  const sendSetup = async () => {
    setError(null)
    try {
      setSetupResp(await api<string>('/api/punchout/setup', { text: setupXml, asText: true }))
    } catch (e) {
      setError((e as Error).message)
    }
  }
  const startUrl = setupResp.match(/<URL>([^<]+)<\/URL>/)?.[1]

  const sendEdi = async () => {
    setError(null)
    setEdiResult(null)
    try {
      setEdiResult(await api<EdiResult>('/api/edi/850', { text: edi }))
    } catch (e) {
      setError((e as Error).message)
    }
  }

  return (
    <div className="space-y-4">
      <PageTitle
        title="eProcurement: PunchOut & EDI"
        subtitle="Large enterprises rarely check out on the distributor's site. Their ERP opens a cXML PunchOut session, gets the priced cart back for internal approval, then transmits the real purchase order as an ANSI X12 850."
      />
      <ol className="grid gap-2 text-sm sm:grid-cols-4">
        {['ERP posts PunchOutSetupRequest', 'Buyer shops with contract prices', 'Cart returns as PunchOutOrderMessage', 'ERP sends EDI 850 → we answer 855'].map((s, i) => (
          <li key={s} className="rounded-lg border border-slate-200 bg-white p-2 dark:border-slate-800 dark:bg-slate-900">
            <span className="mr-1 font-bold text-brand-600">{i + 1}.</span>
            {s}
          </li>
        ))}
      </ol>
      <ErrorBox error={error} />

      {erpReturn && (
        <Card className="border-violet-400 dark:border-violet-700">
          <H2 aside={<Badge tone="violet">{erpReturn.erp}</Badge>}>Step 3 · Cart received by the ERP ({money(erpReturn.total)})</H2>
          <p className="mb-2 text-sm text-slate-600 dark:text-slate-400">
            This cXML is what {erpReturn.erp} receives at its BrowserFormPost URL. The requisition now runs through the customer's own approval workflow. Once it is approved, the ERP
            sends the EDI 850 below (prefilled in step 4).
          </p>
          <pre className="code-block max-h-72">{erpReturn.orderMessage}</pre>
        </Card>
      )}

      <div className="grid gap-4 lg:grid-cols-2">
        <Card className="min-w-0">
          <H2>Step 1 · PunchOut setup (playing the ERP)</H2>
          <div className="mb-2 flex flex-wrap gap-2">
            <select value={identity} onChange={(e) => setIdentity(e.target.value)} aria-label="ERP identity">
              {identities.data?.map((i) => (
                <option key={i.identity} value={i.identity}>
                  {i.erp} · {i.corporation}
                </option>
              ))}
            </select>
            <Button variant="secondary" onClick={loadSample}>
              New request
            </Button>
            <Button onClick={sendSetup}>Send setup request</Button>
          </div>
          <textarea value={setupXml} onChange={(e) => setSetupXml(e.target.value)} rows={14} className="w-full font-mono text-xs" spellCheck={false} aria-label="cXML setup request" />
          <p className="mt-1 text-xs text-slate-500">Edit the SharedSecret to watch authentication fail, or the UserEmail to punch in as a different buyer.</p>
        </Card>
        <Card className="min-w-0">
          <H2>Step 2 · Setup response</H2>
          {setupResp ? (
            <>
              <pre className="code-block">{setupResp}</pre>
              {startUrl && (
                <Button className="mt-3 bg-violet-700 hover:bg-violet-800" onClick={() => navigate(new URL(startUrl).pathname)}>
                  Open StartPage and shop
                </Button>
              )}
            </>
          ) : (
            <p className="text-sm text-slate-500">Send the setup request to receive a StartPage URL for the buyer's browser.</p>
          )}
        </Card>
      </div>

      <div className="grid gap-4 lg:grid-cols-2">
        <Card className="min-w-0">
          <H2>Step 4 · Inbound EDI 850 purchase order</H2>
          <textarea value={edi} onChange={(e) => setEdi(e.target.value)} rows={16} className="w-full font-mono text-xs" spellCheck={false} aria-label="X12 850 document" />
          <div className="mt-2 flex flex-wrap gap-2">
            <Button onClick={sendEdi}>Transmit 850</Button>
            <Button variant="secondary" onClick={() => setEdi(sample850())}>
              Load partner sample
            </Button>
          </div>
          <p className="mt-1 text-xs text-slate-500">
            The sample has a price mismatch on line 1, a Fastenal part number on line 2 and a discontinued SKU on line 3. Separators are read from the ISA segment.
          </p>
        </Card>
        <Card className="min-w-0">
          <H2>855 acknowledgment & resulting order</H2>
          {!ediResult ? (
            <p className="text-sm text-slate-500">Transmit an 850 to see how each line is resolved and acknowledged.</p>
          ) : (
            <div className="space-y-3">
              <div className="flex flex-wrap items-center gap-2 text-sm">
                <Badge tone={ediResult.outcome === 'accepted' ? 'green' : 'red'}>{ediResult.outcome}</Badge>
                {ediResult.partner} · PO {ediResult.po.poNumber}
                {ediResult.orderId && (
                  <Link to={`/orders/${ediResult.orderId}`} className="font-mono text-brand-600 hover:underline dark:text-amber-400">
                    {ediResult.orderId} →
                  </Link>
                )}
              </div>
              {ediResult.po.warnings?.length > 0 && <Callout title="Envelope warnings">{ediResult.po.warnings.join(' ')}</Callout>}
              <table className="w-full text-sm">
                <tbody>
                  {ediResult.lines.map((l) => (
                    <tr key={l.lineNo} className="border-t border-slate-100 align-top dark:border-slate-800">
                      <td className="py-1.5 font-mono text-xs">
                        {l.lineNo}. {l.poPart}
                        {l.sku && l.sku !== l.poPart && <> → {l.sku}</>}
                      </td>
                      <td className="py-1.5">
                        <Badge tone={ackLabel[l.status]?.tone}>{ackLabel[l.status]?.text ?? l.status}</Badge>
                        {l.note && <div className="text-xs text-slate-500">{l.note}</div>}
                      </td>
                      <td className="py-1.5 text-right text-xs tabular-nums">
                        {l.qty} @ {money(l.ourPrice || l.poPrice)}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
              {ediResult.plan && <PlanView plan={ediResult.plan} />}
              <pre className="code-block">{ediResult.ack855}</pre>
            </div>
          )}
        </Card>
      </div>

      <Card>
        <H2>EDI message log</H2>
        {!log.data?.length ? (
          <p className="text-sm text-slate-500">No EDI traffic yet.</p>
        ) : (
          <table className="w-full text-sm">
            <tbody>
              {log.data.map((m) => (
                <tr key={m.id} className="border-t border-slate-100 dark:border-slate-800">
                  <td className="py-1.5 text-xs text-slate-500">{dateTime(m.createdAt)}</td>
                  <td className="py-1.5">
                    <Badge tone={m.direction === 'inbound' ? 'blue' : 'green'}>
                      {m.direction} {m.docType}
                    </Badge>
                  </td>
                  <td className="py-1.5 font-mono text-xs">{m.partner}</td>
                  <td className="py-1.5 text-right font-mono text-xs">{m.orderId ?? '—'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </Card>
    </div>
  )
}
