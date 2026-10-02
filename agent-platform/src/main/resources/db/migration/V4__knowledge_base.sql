-- Regulatory knowledge base. Global (not tenant-scoped): it holds public MAS documents, not customer data.
create extension if not exists vector;

create table kb_document (
    doc_id      text primary key,
    short_name  text        not null,
    title       text        not null,
    source_url  text        not null,
    sha256      text        not null,
    chunk_count int         not null,
    ingested_at timestamptz not null default now()
);

create table kb_chunk (
    chunk_id  text primary key,
    doc_id    text        not null references kb_document (doc_id) on delete cascade,
    ref       text        not null,
    section   text        not null,
    page      int         not null,
    text      text        not null,
    -- text-embedding-3-small; changing the model or dimensions means re-embedding everything.
    embedding vector(1536) not null,
    -- Keyword side of hybrid search. Section titles carry many of the terms people search for.
    tsv       tsvector generated always as (to_tsvector('english', section || ' ' || text)) stored
);

create index kb_chunk_embedding_idx on kb_chunk using hnsw (embedding vector_cosine_ops);
create index kb_chunk_tsv_idx on kb_chunk using gin (tsv);
