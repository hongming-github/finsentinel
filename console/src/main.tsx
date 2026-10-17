import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { BrowserRouter } from 'react-router'
import { App } from './App'
import { restoreSession, userManager } from './auth'
import { claimsOf, type Claims } from './claims'
import { SignIn } from './pages/SignIn'
import { SessionContext } from './session'
import './styles.css'

const queries = new QueryClient({
  defaultOptions: { queries: { staleTime: 5_000, retry: 1, refetchOnWindowFocus: false } },
})

const root = createRoot(document.getElementById('root')!)

function render(claims: Claims | null, problem?: string) {
  root.render(
    <StrictMode>
      {claims && claims.tenant ? (
        <SessionContext value={claims}>
          <QueryClientProvider client={queries}>
            <BrowserRouter>
              <App />
            </BrowserRouter>
          </QueryClientProvider>
        </SessionContext>
      ) : (
        <SignIn problem={problem ?? (claims ? `${claims.username} has no bank (no tenant claim)` : undefined)} />
      )}
    </StrictMode>,
  )
}

userManager.events.addUserLoaded((user) => render(claimsOf(user.access_token)))

restoreSession()
  .then((user) => {
    if (user !== 'redirecting') {
      render(user ? claimsOf(user.access_token) : null)
    }
  })
  .catch((e: unknown) => render(null, e instanceof Error ? e.message : String(e)))
