import { signIn } from '../auth'

export function SignIn({ problem }: { problem?: string }) {
  return (
    <main className="signin">
      <div className="signin-box">
        <div className="brand big"><Shield /> FinSentinel</div>
        <p className="lede">
          Real-time fraud detection with Flink and AI case investigation, for several banks on one platform.
          This console reads the platform's tenant-scoped API with your Keycloak token.
        </p>
        {problem && <div className="notice bad">{problem}</div>}
        <button className="primary" onClick={() => void signIn('/')}>Sign in with Keycloak</button>
        <table className="dev-users">
          <caption>Development users (password = name)</caption>
          <tbody>
            <tr><td><code>alice</code></td><td>analyst</td><td>bank straits</td></tr>
            <tr><td><code>bob</code></td><td>analyst</td><td>bank harbour</td></tr>
            <tr><td><code>sam</code></td><td>supervisor</td><td>bank straits</td></tr>
          </tbody>
        </table>
      </div>
    </main>
  )
}

export function Shield() {
  return (
    <svg viewBox="0 0 32 32" width="22" height="22" aria-hidden>
      <path d="M16 2 4 7v8c0 7.5 5.1 13.4 12 15 6.9-1.6 12-7.5 12-15V7z" fill="var(--accent)" />
      <path d="m11 16 4 4 7-8" stroke="var(--bg)" strokeWidth="2.6" fill="none" strokeLinecap="round" />
    </svg>
  )
}
