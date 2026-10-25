// Thin fetch wrapper. The acting persona travels in X-User-ID; the mirror has
// no login so the same screen can be compared across corporate accounts.

let actingUser = 'u-dana'

export function setActingUser(id: string) {
  actingUser = id
}

export class ApiError extends Error {
  status: number
  constructor(status: number, message: string) {
    super(message)
    this.status = status
  }
}

interface Options {
  method?: string
  json?: unknown
  text?: string
  asText?: boolean
  headers?: Record<string, string>
}

export async function api<T>(path: string, opts: Options = {}): Promise<T> {
  const headers: Record<string, string> = { ...opts.headers, 'X-User-ID': actingUser }
  let body: string | undefined
  if (opts.json !== undefined) {
    headers['Content-Type'] = 'application/json'
    body = JSON.stringify(opts.json)
  } else if (opts.text !== undefined) {
    headers['Content-Type'] = 'text/plain'
    body = opts.text
  }
  const res = await fetch(path, { method: opts.method ?? (body ? 'POST' : 'GET'), headers, body })
  if (opts.asText) {
    const t = await res.text()
    if (!res.ok && !t.startsWith('<?xml')) throw new ApiError(res.status, t)
    return t as T
  }
  const data = await res.json().catch(() => ({}))
  if (!res.ok) throw new ApiError(res.status, (data as { error?: string }).error ?? res.statusText)
  return data as T
}
