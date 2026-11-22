-- Spec 98 — Config fina por lojista + controle de conta (backend escrita).
-- Colunas ADITIVAS em merchants (1:1 — sem tabela separada, KISS: os gates de
-- runtime leem direto no lojista). Overrides da Spec 97 (painel) quando
-- presentes; null = usa o default global.
--
-- Coordenação (2026-08-15, revisada): `gateway_route` NÃO é criada aqui — a
-- rota por lojista passou a ser **por perfil de operação** (`merchant_profiles`,
-- Spec 122, migration V69 — dona da entidade). O master console desta spec não
-- gerencia rota de gateway.

ALTER TABLE merchants
  ADD COLUMN pix_fee_type        VARCHAR(8)  NULL,          -- 'FIXED' | 'PERCENT' | NULL
  ADD COLUMN pix_fee_fixed       NUMERIC(15,2) NULL,
  ADD COLUMN pix_fee_percent     NUMERIC(5,2)  NULL,        -- 0-100
  ADD COLUMN pix_fee_threshold   NUMERIC(15,2) NULL,        -- valor de corte p/ PERCENT
  ADD COLUMN withdrawal_fee      NUMERIC(15,2) NULL,        -- override do global (Spec 88)
  ADD COLUMN pix_min             NUMERIC(15,2) NULL,
  ADD COLUMN pix_max             NUMERIC(15,2) NULL,
  ADD COLUMN withdrawal_min      NUMERIC(15,2) NULL,
  ADD COLUMN withdrawal_max      NUMERIC(15,2) NULL,
  ADD COLUMN rate_limit_rps      INTEGER      NULL,         -- override req/s
  ADD COLUMN rate_limit_rpm      INTEGER      NULL,         -- override req/min
  ADD COLUMN api_blocked         BOOLEAN NOT NULL DEFAULT FALSE,
  ADD COLUMN honeypot            BOOLEAN NOT NULL DEFAULT FALSE;

-- MerchantStatus.BANNED é VARCHAR(20) sem CHECK (V1) — o enum novo + os gates
-- que já usam `status` tratam BANNED como bloqueado (sem migration de constraint).
