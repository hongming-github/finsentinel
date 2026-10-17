import { InMemoryWebStorage, UserManager, WebStorageStateStore, type User } from 'oidc-client-ts'
import { KEYCLOAK } from './links'

// Sign-in through Keycloak (ADR 0014, ADR 0027): authorization code with PKCE (S256) as the public client
// "finsentinel-console". Tokens live in memory only, never in localStorage, where any script on the origin could read
// them; a reload signs in again, silently while Keycloak's session lasts. Only the short-lived PKCE state of a
// sign-in in flight goes to sessionStorage, because it must survive the redirect.
export const userManager = new UserManager({
  authority: `${KEYCLOAK}/realms/finsentinel`,
  client_id: 'finsentinel-console',
  redirect_uri: `${window.location.origin}/callback`,
  post_logout_redirect_uri: `${window.location.origin}/`,
  response_type: 'code',
  scope: 'openid',
  userStore: new WebStorageStateStore({ store: new InMemoryWebStorage() }),
  stateStore: new WebStorageStateStore({ store: window.sessionStorage }),
  // Access tokens last five minutes in this realm; the refresh token renews them before they expire.
  automaticSilentRenew: true,
  loadUserInfo: false,
})

/** Set while signed in, so a reload goes straight back through Keycloak instead of showing the sign-in page. */
const SIGNED_IN = 'finsentinel.signedIn'

export function signIn(returnTo: string = window.location.pathname + window.location.search) {
  return userManager.signinRedirect({ state: { returnTo } })
}

export async function signOut() {
  sessionStorage.removeItem(SIGNED_IN)
  await userManager.signoutRedirect()
}

/**
 * The signed-in user after a page load: finishes a sign-in coming back from Keycloak, and otherwise restarts one when
 * the user was signed in before the reload. Null means "show the sign-in page"; "redirecting" means the page is
 * about to leave for Keycloak.
 */
export async function restoreSession(): Promise<User | null | 'redirecting'> {
  if (window.location.pathname === '/callback') {
    const user = await userManager.signinRedirectCallback()
    sessionStorage.setItem(SIGNED_IN, 'true')
    const state = user.state as { returnTo?: string } | undefined
    const returnTo = state?.returnTo?.startsWith('/') && !state.returnTo.startsWith('//') ? state.returnTo : '/'
    window.history.replaceState(null, '', returnTo === '/callback' ? '/' : returnTo)
    return user
  }
  const user = await userManager.getUser()
  if (user && !user.expired) {
    return user
  }
  if (sessionStorage.getItem(SIGNED_IN)) {
    await signIn()
    return 'redirecting'
  }
  return null
}

/** The current access token, renewed if it has expired; signs in again when that fails. */
export async function accessToken(): Promise<string> {
  let user = await userManager.getUser()
  if (!user || user.expired) {
    try {
      user = await userManager.signinSilent()
    } catch {
      user = null
    }
  }
  if (!user) {
    await signIn()
    throw new Error('signing in again')
  }
  return user.access_token
}
