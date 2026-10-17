import { NavLink, Route, Routes } from 'react-router'
import { signOut } from './auth'
import { isSupervisor } from './claims'
import { FAKE_LLM, FLINK_UI, kafkaTopics, keycloakAdmin, pipelineDashboard } from './links'
import { Alerts } from './pages/Alerts'
import { Audit } from './pages/Audit'
import { CaseDetailPage } from './pages/CaseDetail'
import { Cases } from './pages/Cases'
import { Overview } from './pages/Overview'
import { Shield } from './pages/SignIn'
import { Usage } from './pages/Usage'
import { useSession } from './session'
import { External } from './ui'

export function App() {
  const session = useSession()
  const supervisor = isSupervisor(session)
  return (
    <div className="shell">
      <aside className="nav">
        <div className="brand"><Shield /> FinSentinel</div>
        <nav>
          <NavLink to="/" end>Overview</NavLink>
          <NavLink to="/cases">Cases</NavLink>
          <NavLink to="/alerts">Alerts</NavLink>
          <NavLink to="/usage" className={({ isActive }) => (isActive ? 'active' : supervisor ? '' : 'locked')}>
            LLM usage
          </NavLink>
          <NavLink to="/audit" className={({ isActive }) => (isActive ? 'active' : supervisor ? '' : 'locked')}>
            Audit log
          </NavLink>
        </nav>
        <div className="nav-section">Tools</div>
        <nav className="tools">
          <External href={pipelineDashboard()}>Grafana dashboard</External>
          <External href={FLINK_UI}>Flink UI</External>
          <External href={kafkaTopics}>Kafka UI</External>
          <External href={`${FAKE_LLM}/admin/behaviour`}>fake-llm behaviour</External>
          <External href={keycloakAdmin}>Keycloak admin</External>
        </nav>
      </aside>
      <div className="main">
        <header className="topbar">
          <div className="who">
            <span className="tenant" title="From the token's tenant claim; every request is scoped to it">
              bank <b>{session.tenant}</b>
            </span>
            <span>{session.username}</span>
            {session.roles.filter((r) => r === 'analyst' || r === 'supervisor').map((r) => (
              <span key={r} className="role">{r}</span>
            ))}
          </div>
          <button className="ghost" onClick={() => void signOut()}>Sign out</button>
        </header>
        <main className="content">
          <Routes>
            <Route path="/" element={<Overview />} />
            <Route path="/cases" element={<Cases />} />
            <Route path="/cases/:caseId" element={<CaseDetailPage />} />
            <Route path="/alerts" element={<Alerts />} />
            <Route path="/usage" element={<Usage />} />
            <Route path="/audit" element={<Audit />} />
            <Route path="*" element={<div className="notice">Nothing here.</div>} />
          </Routes>
        </main>
      </div>
    </div>
  )
}
