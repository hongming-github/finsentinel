import { createContext, useContext } from 'react'
import type { Claims } from './claims'

/** Who is signed in: the token's claims, display only (the platform decides what they may do). */
export const SessionContext = createContext<Claims | null>(null)

export function useSession(): Claims & { tenant: string } {
  const claims = useContext(SessionContext)
  if (!claims || !claims.tenant) {
    throw new Error('no tenant in the token')
  }
  return claims as Claims & { tenant: string }
}
