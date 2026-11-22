---
name: Resiliência operacional pós-análise
status: audited
references:
  - "management/vision.md#V46"
  - "docs/analise-tecnica-2026-08-10.md"
  - "docs/roteiro-melhorias-2026-08-10.md"
  - "management/specs/63-edge-waf-bot-mitigation/requirements.md"
  - "management/specs/64-sec-observability-incident-response/requirements.md"
  - "management/adr.md#ADR-010"
  - "management/adr.md#ADR-014"
---

# Spec 66 — Resiliência operacional pós-análise

## O que entrega

As **recomendações** da análise técnica completa de 2026-08-10
(`docs/analise-tecnica-2026-08-10.md`), com os riscos transformados em entregas
coesas. Fecha 3 lacunas operacionais medidas na sessão:

1. **Rollback de edge completo (Fase A)** — o achado do critério 9 da Spec 63:
   com proxy OFF o DNS resolve para o Railway, mas o **HTTPS direto falha**
   (`Connection was reset` — o Railway não serve cert de `api.foxcash.example.com` no
   IP do domínio default). A correção é registrar `api.foxcash.example.com` como
   **custom domain de origem** no Railway (o CNAME do Cloudflare passa a apontar
   para o endpoint de custom domain do Railway, que tem cert válido) — proxy OFF
   volta a restaurar o serviço, e o runbook `deploy/edge.md` §Rollback fica
   correto e testado.
2. **Alertas de segurança agregados entre réplicas (Fase B)** — hoje os
   contadores de rajada do `SecurityAlertPublisher` (Spec 64) são **por-instância**:
   com 2 réplicas, um flood distribuído pode não atingir o limiar (ex.: 5/60s do
   `BODY_INSPECTION_BLOCKED` → 3+2 = 0 alertas). Os contadores passam a ser
   agregados via **Redis** (script Lua INCR/EXPIRE, **fail-open** → fallback
   local quando o Redis cai — ADR-010). O limiar vira **global** (N réplicas somam).
3. **Monitoramento proativo do body inspection (Fase C)** — além do script
   `ops/monitor-body-inspection.sh`, o filtro expõe uma **métrica Micrometer**
   `nyx.body_inspection.blocks` (contador por padrão/categoria + total) no
   `/actuator/prometheus`, e um **runbook de calibração** documenta quando e como
   ajustar padrões/reverter (`NYX_BODY_INSPECTION_ENABLED=false`).

**Fora de escopo** (decisões já registradas em ADR/docs — não reabrir sem
evidência): PgBouncer/réplicas de leitura (ADR-013 — gated por benchmark),
DLQ física do outbox (decisão de simplicidade da Spec 53), autenticação
adaptativa/step-up (proposta futura), gestão de segredos Doppler/Vault (proposta
futura), upgrade de plano Cloudflare (regra de ouro: só com evidência de ataque).

## Critérios de aceite

1. **Rollback testado ao vivo**: proxy OFF → `curl -I https://api.foxcash.example.com`
   responde **200** direto do Railway (sem Cloudflare), TLS válido (sem erro de
   certificado); proxy ON restaurado em seguida. Criterion 9 da Spec 63 fechado
   com evidência. Runbook `deploy/edge.md` §Rollback atualizado com o
   procedimento correto (custom domain do Railway).
2. **Agregação entre réplicas**: com 2 réplicas e Redis ativo, N bloqueios
   distribuídos (ex.: 3 numa réplica + 2 noutra, dentro da janela) disparam
   **1** alerta `BODY_INSPECTION_BLOCKED` (limiar 5/60s **global**). Com Redis
   indisponível → **fail-open**: contadores voltam ao comportamento local atual
   (WARN throttled) — nunca derruba nem degrada o caminho de bloqueio.
3. **Métrica**: `nyx.body_inspection.blocks` presente no
   `/actuator/prometheus` (ROLE_ADMIN) com tags `pattern` e `category` + total;
   um bloqueio real incrementa o contador.
4. **Runbook de calibração** documentado (deploy/edge.md §7.5 + Fase C):
   quando ajustar padrões, como coletar evidência (monitor + métrica + logs),
   reversão imediata (`NYX_BODY_INSPECTION_ENABLED=false`).
5. `mvn test` sem regressão + E2E sem regressão (mudanças de runtime/config não
   alteram contrato; o alerta continua fail-safe).

## Filosofia

- **Disponibilidade primeiro (ADR-010)**: a agregação de alertas é **fail-open**
  — Redis fora não muda o comportamento de bloqueio nem derruba o gateway; só
  perde a visão agregada (volta ao por-instância, com WARN).
- **Evidência antes de mudança**: a Fase A só altera a topologia porque o achado
  foi **medido** (handshake reset verificado ao vivo); o runbook é atualizado com
  o procedimento testado.
- **O edge reduz, o app controla**: nada aqui substitui controle da app — a
  métrica/calibração só tornam o body inspection **operável**.
- **Reversível**: Fase A = config de DNS/Railway (minutos); Fase B = env
  (`foxcash.security.alert.store=memory|redis`); Fase C = métrica + docs.

## Dependências

- `management/specs/63-edge-waf-bot-mitigation/` (edge/Cloudflare + body inspection)
- `management/specs/64-sec-observability-incident-response/` (SecurityAlertPublisher,
  WebhookEventType, runbook de incidente)
- `management/adr.md` (ADR-010 fail-open; ADR-014 edge)
- `deploy/edge.md` (runbook a atualizar) · `ops/monitor-body-inspection.sh`
- `docs/analise-tecnica-2026-08-10.md` e `docs/roteiro-melhorias-2026-08-10.md`
