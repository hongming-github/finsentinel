# Python MCP client: an analyst's assistant

A LangGraph agent in Python that uses the FinSentinel platform (Java) only through its MCP server
(`http://localhost:8090/mcp`): `listCases`, `getCase`, `searchRegulations`, `readRegulationPassages`. The platform's
own agents stay in Java; this shows that any agent stack can reuse its case data and knowledge base (ADR 0002).

```bash
cd examples/mcp-client-python
uv run analyst_assistant.py "Which straits cases await a decision? Summarise the riskiest one."
```

Needs the platform running and `OPENAI_API_KEY`. Regulation searches count against the platform's
`finsentinel.mcp-server.max-regulation-searches-per-hour` budget.
