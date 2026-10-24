export interface User {
  id: string
  name: string
  email: string
  title: string
  role: string
  corporationId: string
  corporation: string
  facilityId: string
  costCenterId: string
  perOrderLimit: number
  monthlyLimit: number
  approverId: string | null
  certifications: string[]
  badgeId: string | null
}

export interface Approver {
  id: string
  name: string
  title: string
  perOrderLimit: number
}

export interface CostCenterOption {
  id: string
  name: string
  code: string
  facilityId: string
  facility: string
  state: string
}

export interface BlanketPO {
  id: string
  facilityId: string | null
  poNumber: string
  authorized: number
  consumed: number
  validFrom: string
  validTo: string
}

export interface Me {
  user: User
  approverChain: Approver[]
  mtdSpend: number
  costCenters: CostCenterOption[]
  orgPath: string[]
  blanketPOs: BlanketPO[]
  pendingApprovals: number
}

export interface AttrDef {
  key: string
  label: string
  type: 'number' | 'enum' | 'text' | 'bool'
  unit?: string
  facet: boolean
}

export interface CategoryNode {
  id: string
  parentId: string | null
  name: string
  unspsc: string
  attributeSchema: AttrDef[]
  count: number
  children: CategoryNode[]
}

export interface Tier {
  categoryId: string
  minQty: number
  pct: number
}

export interface Restriction {
  categoryName: string
  cert: string
}

export interface PriceCandidate {
  source: string
  label: string
  unitPrice: number
  applies: boolean
  reason?: string
}

export interface PriceResult {
  listPrice: number
  unitPrice: number
  qty: number
  extended: number
  source: string
  label: string
  savingsPct: number
  candidates: PriceCandidate[]
  nextTier?: Tier
}

export interface Product {
  sku: string
  categoryId: string
  name: string
  brand: string
  mfrPartNumber: string
  description: string
  listPrice: number
  uom: string
  weightLb: number
  attributes: Record<string, string | number | boolean>
  hazmatUn: string | null
  hazmatClass: string | null
  sdsUrl: string | null
  dropShipOnly: boolean
  status: 'active' | 'discontinued'
  replacementSku: string | null
}

export interface Availability {
  network: number
  localBranch: number
  branchName: string
}

export interface SearchItem extends Product {
  categoryName: string
  price: PriceResult
  availability: Availability
}

export interface FacetValue {
  value: string
  count: number
  selected: boolean
}

export interface Facet extends AttrDef {
  values: FacetValue[]
}

export interface CrossRefHit {
  refType: string
  refBrand: string
  refPartNumber: string
  note: string
  sku: string
  name: string
  brand: string
}

export interface SearchResponse {
  query: string
  category: string
  total: number
  items: SearchItem[]
  facets: Facet[]
  categoryFacets: { id: string; name: string; count: number }[]
  crossRefs: CrossRefHit[]
}

export interface NodeInfo {
  id: string
  kind: 'dc' | 'branch' | 'supplier'
  name: string
  lat: number
  lon: number
  hazmatCapable: boolean
  willCall: boolean
  leadDays: number
}

export interface ProductDetail {
  product: Product
  category: { id: string; name: string; unspsc: string; attributeSchema: AttrDef[] }
  breadcrumb: { id: string; name: string }[]
  price: PriceResult
  tiers: Tier[] | null
  restriction: Restriction | null
  crossRefs: CrossRefHit[]
  equivalents: (Product & { price: PriceResult })[]
  replacement: (Product & { price: PriceResult }) | null
  inventory: (NodeInfo & { atp: number; distanceMi: number; stocked: boolean })[]
  priceByCustomer: { corporationId: string; corporation: string; price: PriceResult }[]
  facility: { id: string; name: string }
}

export interface Check {
  code: string
  level: 'pass' | 'info' | 'approval' | 'block'
  message: string
}

export interface Governance {
  outcome: 'auto_approved' | 'approval_required' | 'blocked'
  checks: Check[]
  approver?: Approver
}

export interface Allocation {
  sku: string
  qty: number
}

export interface PlannedShipment {
  nodeId: string
  nodeName: string
  nodeKind: string
  method: string
  etaDays: number
  etaLabel: string
  distanceMi: number
  lines: Allocation[]
  note?: string
}

export interface Plan {
  shipments: PlannedShipment[]
  backorders: Allocation[]
  backorderEtaDays: number
  split: boolean
  heldForConsolidation: boolean
  notes: string[]
}

export interface PricedLine {
  lineNo: number
  sku: string
  name: string
  brand: string
  uom: string
  qty: number
  unitPrice: number
  extended: number
  price: PriceResult
  hazmat: boolean
  dropShipOnly: boolean
  restriction?: Restriction
}

export interface Evaluation {
  lines: PricedLine[]
  subtotal: number
  taxRate: number
  tax: number
  taxNote: string
  total: number
  governance: Governance
  plan: Plan
  facility: { id: string; name: string; state: string }
  costCenter: { id: string; name: string; code: string }
  mtdSpend: number
}

export interface OrderSummary {
  id: string
  userId: string
  userName: string
  costCenter: string
  facility: string
  poNumber: string
  source: 'web' | 'punchout' | 'edi' | 'keepstock'
  status: 'pending_approval' | 'released' | 'shipped' | 'rejected'
  total: number
  approverId: string | null
  approverName: string | null
  createdAt: string
  lineCount: number
  deliveryMethod: string
}

export interface OrderDetail {
  order: OrderSummary
  subtotal: number
  tax: number
  taxNote: string
  fulfillmentPref: string
  governance: Check[]
  approvals: { by: string; byName: string; action: string; note: string; at: string }[]
  lines: { lineNo: number; sku: string; name: string; uom: string; qty: number; unitPrice: number; priceSource: string; extended: number; ref: string | null }[]
  shipments: { id: number; nodeId: string | null; nodeName: string | null; method: string; etaDays: number; etaLabel: string; status: string; note: string; lines: Allocation[] }[]
}

export interface CartLine {
  sku: string
  qty: number
}
