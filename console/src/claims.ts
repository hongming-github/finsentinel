// What the console shows about the signed-in user, read from the access token. Display only: the platform verifies the
// token and takes the tenant from it (ADR 0014); the console never sends a tenant of its own choosing.

export interface Claims {
  username: string
  tenant: string | null
  roles: string[]
  clientId: string | null
  expiresAt: Date | null
}

export function claimsOf(accessToken: string): Claims {
  const payload = accessToken.split('.')[1]
  if (!payload) {
    throw new Error('not a JWT')
  }
  const json = JSON.parse(base64UrlDecode(payload)) as Record<string, unknown>
  const roles = Array.isArray(json.roles) ? json.roles.filter((r): r is string => typeof r === 'string') : []
  return {
    username: str(json.preferred_username) ?? str(json.sub) ?? 'unknown',
    tenant: str(json.tenant),
    roles,
    clientId: str(json.azp),
    expiresAt: typeof json.exp === 'number' ? new Date(json.exp * 1000) : null,
  }
}

export const isAnalyst = (c: Claims) => c.roles.includes('analyst')
export const isSupervisor = (c: Claims) => c.roles.includes('supervisor')

function str(v: unknown): string | null {
  return typeof v === 'string' && v.length > 0 ? v : null
}

function base64UrlDecode(s: string): string {
  const base64 = s.replace(/-/g, '+').replace(/_/g, '/').padEnd(Math.ceil(s.length / 4) * 4, '=')
  const bytes = Uint8Array.from(atob(base64), (c) => c.charCodeAt(0))
  return new TextDecoder().decode(bytes)
}
