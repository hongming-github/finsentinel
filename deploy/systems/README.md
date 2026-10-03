# Fake bank systems (WireMock)

Stand-ins for systems the platform does not own, reached only through the MCP servers (ADR 0002):

| Path | System | Used by |
|---|---|---|
| `/crm/{tenant}/customers/{customerId}` | CRM: KYC profile, travel notices, staff notes | `mcp-banking` |
| `/core/{tenant}/accounts/{accountId}` | Core banking: account and recent security events | `mcp-banking` |
| `/screening/{tenant}/counterparties/{counterpartyId}` | Watchlist screening (inter-bank mule accounts) | `mcp-compliance` |

Every id gets a plain default answer. A few id patterns carry a story, so simulator traffic runs into them:

| Pattern | Story |
|---|---|
| customer id ending in `3` | Travel notice to Japan, started three days ago |
| customer id ending in `66` | A CRM note with a prompt injection aimed at the agents |
| account id ending in `7` | Account-takeover signals: phone number changed, new device, transfer limit raised |
| counterparty `payee-new-…` ending in `0` or `5` | On the inter-bank mule account list |

Dates are relative to the request time. Used by `docker compose --profile systems` and by the MCP servers' tests.
