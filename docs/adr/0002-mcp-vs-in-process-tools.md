# 0002. MCP for systems we do not own; in-process tools for our own data

- Status: accepted (implementation in phase 3)
- Date: 2026-09-30

## Context

Investigation agents need tools: querying our own alerts and transactions, and calling external systems (CRM,
core banking, sanctions screening, ticketing). MCP gives a standard protocol for tools, but it adds a network hop,
serialization, and an auth boundary on every call.

## Decision

| Tool | Mechanism | Why |
|---|---|---|
| CRM, core banking | MCP (`mcp-banking`) | Owned by another team; the boundary exists anyway |
| Sanctions screening, ticketing | MCP (`mcp-compliance`) | Third-party / vendor systems |
| Our alerts, transactions, cases | In-process `@Tool` | Same process and database; a hop buys nothing |
| Compliance knowledge base (RAG) | In-process, **also** exposed via our own MCP server | Low latency inside; reusable from Claude Desktop/Code outside |

The platform is both an MCP **client** (to the two servers above) and an MCP **server** (exposing case lookup and
compliance search to external agents).

## Consequences

- Adding a new external system means adding an MCP server, not changing agent code.
- Every MCP call is authenticated (OAuth 2.1, Keycloak), traced, time-limited, and audited.
- Tool results from MCP servers are untrusted input: free-text fields (e.g. CRM notes) can carry indirect
  prompt injection, and there is a test for that.
