---
name: Compression de resposta (gzip) habilitada
status: audited
references:
  - "management/vision.md#V36"
  - "management/specs/53-job-robustez/requirements.md"
---

# Spec 56 — Compression de resposta (gzip)

## O que entrega

Habilita a **compressão de resposta HTTP** (`server.compression.enabled=true`):
payloads JSON de produção (storefront, vendas, relatórios, listagens paginadas)
passam a trafegar com `content-encoding: gzip` quando o cliente envia
`Accept-Encoding: gzip`. Reduz largura de banda e latência percebida em
produção (Railway) — verificado na auditoria 2026-08-07 que nenhuma resposta
vinha comprimida.

Configuração por default: mime-types JSON/XML/texto (a API é 100% JSON; incluir
os text/* cobre respostas de erro e qualquer conteúdo futuro) e tamanho mínimo
de 1KB (respostas minúsculas não compensam o overhead do gzip).

## Critérios de aceite

1. `GET`/`POST` com `Accept-Encoding: gzip` em rota com resposta JSON (ex.:
   `/actuator/prometheus` com admin, `/api/v1/foxcash/storefront`) → resposta com
   `content-encoding: gzip` e `vary: accept-encoding`.
2. Resposta menor que o tamanho mínimo (1KB) → **sem** compressão (economia de
   CPU).
3. Respostas de erro (`401`/`400`/`404` — <1KB) → sem compressão (comportamento
   correto; o conteúdo não é degradado).
4. Cliente **sem** `Accept-Encoding` → resposta normal sem `content-encoding`
   (compatibilidade total).
5. `mvn test` sem regressão; E2E sem regressão.
