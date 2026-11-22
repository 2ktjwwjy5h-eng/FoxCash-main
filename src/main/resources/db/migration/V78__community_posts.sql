-- Spec 119 (Lote 10): Comunidade — avisos/novidades da plataforma (read-only no
-- portal; CRUD no Master Console). Sem chat/comentários (KISS — fora de escopo).
-- A última migration real era a V77; V78 é o próximo número livre.

CREATE TABLE community_posts (
    id           UUID PRIMARY KEY,
    title        VARCHAR(160) NOT NULL,
    content      TEXT NOT NULL,
    published_by UUID NOT NULL REFERENCES masters(id),
    pinned       BOOLEAN NOT NULL DEFAULT FALSE,
    published_at TIMESTAMPTZ NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL
);

CREATE INDEX ix_community_posts_published ON community_posts (published_at DESC);
