import { describe, expect, it } from 'vitest'
import { claimsOf, isAnalyst, isSupervisor } from './claims'

const jwt = (claims: object) =>
  ['{"alg":"none"}', JSON.stringify(claims)].map((p) => Buffer.from(p).toString('base64url')).join('.') + '.sig'

describe('claimsOf', () => {
  it('reads the tenant, roles and user the platform relies on', () => {
    const c = claimsOf(jwt({ sub: 's-1', preferred_username: 'sam', tenant: 'straits', roles: ['supervisor'],
      azp: 'finsentinel-console', exp: 1_800_000_000 }))
    expect(c).toMatchObject({ username: 'sam', tenant: 'straits', roles: ['supervisor'], clientId: 'finsentinel-console' })
    expect(c.expiresAt?.toISOString()).toBe('2027-01-15T08:00:00.000Z')
    expect(isSupervisor(c)).toBe(true)
    expect(isAnalyst(c)).toBe(false)
  })

  it('has no tenant when the token has none, rather than guessing one', () => {
    const c = claimsOf(jwt({ sub: 'service-account-platform-service', roles: 'not-a-list' }))
    expect(c.tenant).toBeNull()
    expect(c.roles).toEqual([])
    expect(c.username).toBe('service-account-platform-service')
  })

  it('decodes UTF-8 names', () => {
    expect(claimsOf(jwt({ preferred_username: '赵' })).username).toBe('赵')
  })

  it('refuses something that is not a JWT', () => {
    expect(() => claimsOf('opaque-token')).toThrow()
  })
})
