-- What a chunk's cross-references point to ("Refers to: §6 (CUSTOMER DUE DILIGENCE)"), from the documents' own
-- headings (ADR 0030). Shown to the reranker only; never embedded, and the agent quotes only the text. Empty while
-- finsentinel.knowledge.context-chars is 0 (the default).
alter table kb_chunk add column context text not null default '';
