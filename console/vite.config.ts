import react from '@vitejs/plugin-react'
import { defineConfig, type ProxyOptions } from 'vite'

// The console's own origin serves the platform's API and every process's health check (ADR 0027). The browser never
// calls them cross-origin, so none of them needs a CORS rule; the only cross-origin calls go to Keycloak, which allows
// this origin through the client's web origins. Each health route maps to exactly one URL of its process, nothing else.
const health: Record<string, string> = {
  platform: 'http://localhost:8090/actuator/health',
  'mcp-banking': 'http://localhost:8091/actuator/health',
  'mcp-compliance': 'http://localhost:8092/actuator/health',
  'fake-llm': 'http://localhost:8096/health',
  flink: 'http://localhost:8082/jobs/overview',
  keycloak: 'http://localhost:8094/realms/finsentinel/.well-known/openid-configuration',
  'schema-registry': 'http://localhost:8095/apis/ccompat/v7/subjects',
  grafana: 'http://localhost:3000/api/health',
  'kafka-ui': 'http://localhost:8098/actuator/health',
}

function exactly(url: string): ProxyOptions {
  const target = new URL(url)
  return { target: target.origin, changeOrigin: true, rewrite: () => target.pathname + target.search }
}

const proxy: Record<string, ProxyOptions> = {
  '/api/': { target: 'http://localhost:8090', changeOrigin: true },
  ...Object.fromEntries(Object.entries(health).map(([name, url]) => [`/health/${name}`, exactly(url)])),
}

export default defineConfig({
  plugins: [react()],
  // 8097: next to the platform's other ports (809x); the Keycloak client redirects only here.
  server: { port: 8097, strictPort: true, proxy },
  preview: { port: 8097, strictPort: true, proxy },
})
