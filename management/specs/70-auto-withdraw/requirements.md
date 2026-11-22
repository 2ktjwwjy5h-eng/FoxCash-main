---
name: Saque automático por limiar de saldo (auto-withdraw)
status: audited
references:
  - "management/vision.md#V50"
  - "management/specs/14-withdrawals/requirements.md"
  - "management/specs/31-withdrawal-destinations/requirements.md"
  - "management/specs/32-withdrawal-destination-flow/requirements.md"
  - "management/specs/36-payout-psp/requirements.md"
  - "management/specs/41-master-profiles-activity/requirements.md"
  - "management/specs/53-job-robustez/requirements.md"
  - "management/specs/46-portal-frontend/requirements.md"
  - "management/specs/26-qa-hardening-financeiro/requirements.md"
---

# Spec 70 — Saque automático por limiar de saldo (auto-withdraw)

## O que entrega

Dá ao vendedor a **opção de saque automático**: quando o **saldo disponível no
wallet** dele atingir (ou passar) um **limiar** (default R$ 1.000,00 — decisão do
usuário), o sistema saca **todo o saldo disponível** automaticamente para um
**destino específico escolhido pelo vendedor** (diferente do saque manual). A
ativação/desativação é feita pelo próprio vendedor, **no painel do portal**.

Decisões do usuário (2026-08-11):

| Aspecto | Decisão |
|---|---|
| Gatilho | Saldo disponível no wallet (`wallet_balance`) `>=` limiar (não volume de vendas) |
| Valor sacado | Todo o saldo disponível |
| Destino | Escolhido pelo vendedor (destino específico para o automático) |
| Ativação | Configuração no painel do portal (toggle + limiar configurável) |

A implementação **reusa todo o pipeline de saque existente** (Specs 14/31/32/36):
destino ativo, bloqueio do master, inadimplência, ledger (`WITHDRAWAL_DEBIT`),
Fase A/B (débito + PSP fora da transação), webhook de saída e reconciliação.
Nenhuma regra financeira existente é enfraquecida — o saque automático é **um
saque de vendedor comum**, disparado por um job, com as mesmas validações. Uma
condição bloqueante (lojista não-ACTIVE, saques bloqueados, dívida aberta,
destino inativo/removido, saldo abaixo do limiar na hora do lock) resulta em
**skip silencioso** — o job nunca quebra nem gera saque indevido.

## Contexto (por que agora)

O vendedor hoje precisa abrir o portal, ver o saldo e solicitar o saque
manualmente (`POST /merchants/{id}/withdrawals`, Spec 32). Para operações com
muitas vendas, acompanhar o saldo e sacar repetidamente é fricção. O usuário
pediu (2026-08-11) uma **opção de saque automático acima de R$ 1.000**: o
vendedor ativa a opção e, quando o saldo cruza o limiar, o dinheiro vai sozinho
para o destino escolhido. O subsistema de saque já tem tudo o que é preciso
(destinos, bloqueios, ledger, PSP de saída, webhook de conclusão) — falta apenas
a **configuração** (portal) e o **gatilho agendado** (job). Isso mantém o
princípio do projeto (jobs com ShedLock + `JobExecutionService`, Spec 53) e não
toca o caminho crítico da venda (o gatilho é assíncrono, fora do `POST /charges`).

## Critérios de aceite

1. **Modelo de configuração persistido**: migration adiciona a tabela
   `merchant_auto_withdraw_config` (1:1 por lojista — `merchant_id` **UNIQUE**,
   FK `merchants.id` ON DELETE CASCADE; `enabled BOOLEAN`; `threshold_amount
   NUMERIC(15,2)`; `destination_id` FK `merchant_withdrawal_destinations.id`;
   `created_at`/`updated_at`). Não há configuração por default (linha ausente =
   desabilitado).

2. **API de configuração no portal** (`ROLE_MERCHANT_PORTAL`; sempre o próprio
   lojista — sem parâmetro de lojista, impossível IDOR):
   - `GET /api/v1/portal/auto-withdraw` → 200 `{enabled, threshold,
     destinationId}`. **Nunca 404**: nunca configurado → `{enabled: false,
     threshold: <default da plataforma>, destinationId: null}` (settings com
     default do backend — o frontend não duplica o limiar).
   - `PUT /api/v1/portal/auto-withdraw` body `{enabled, threshold,
     destinationId?}` → 200 com o recurso atualizado (upsert: cria se ausente,
     atualiza se presente).
   - Validações do PUT: `threshold` obrigatório, `> 0` (validação do serviço —
     `Money` permite negativo, cada uso valida seu domínio) e `≤ 2 casas
     decimais` (regra `Money`/M9) e `≥ min-threshold` (config default R$ 1,00) →
     400 `invalid_argument`; `enabled=true` exige `destinationId` → 400; destino
     inexistente ou de outro lojista → 404 `destination_not_found` (sem revelar
     existência); destino inativo → 409 `destination_inactive`. `enabled=false`
     não exige `destinationId` (mantém/limpa o anterior).

3. **Auditoria**: `AUTO_WITHDRAW_SETTINGS_UPDATED` gravado no
   `platform_activity_log` na mesma transação (Spec 41), actor `MERCHANT`, sem
   dados sensíveis (apenas `enabled`/`threshold`/`destinationId` no `details`).

4. **Job agendado** `AutoWithdrawalScheduler` com ShedLock
   (`foxcash-auto-withdraw`), cron configurável `foxcash.auto-withdraw.cron`
   (default `0 */5 * * * *` UTC) e execução delegada ao `JobExecutionService`
   (Spec 53 — `job_runs` + métricas `nyx_job_*` + alerta `JOB_FAILED`).
   Kill-switch global `foxcash.auto-withdraw.enabled` (default `true`): com o
   switch desligado o job não executa nenhum saque (operação).

5. **Gatilho e valor**: para cada lojista com config `enabled=true`, se
   `wallet_balance >= threshold` **sob o lock pessimista do lojista** → cria um
   saque `MERCHANT` `PROCESSING` no valor **igual a todo o saldo disponível**
   (decisão do usuário), no destino configurado, reusando a Fase A
   (`WithdrawalProcessingPersister` — débito + ledger `WITHDRAWAL_DEBIT` na
   mesma transação) e a Fase B (PSP fora da transação +
   `WithdrawalSubmissionPersister.submit`). O valor é determinado **dentro do
   lock** (sem corrida: nada de resíduo por crédito concorrente).

6. **Regras existentes respeitadas** (mesmas do saque manual): lojista ACTIVE,
   saques desbloqueados (`withdrawals_blocked=false`), sem dívida aberta
   (`outstanding_debt`/receivable `OPEN`), destino ativo e do próprio lojista.
   Condição bloqueante → **skip silencioso** (log INFO/WARN, sem exceção
   propagada e sem job `FAILED`): não-ACTIVE, bloqueado, inadimplente, destino
   inativo/removido, ou saldo abaixo do limiar no momento do lock (corrida com
   refund/estorno).

7. **Anti-duplo**: uma execução do job com saldo `>=` limiar gera **um único**
   saque; o débito da Fase A deixa o saldo abaixo do limiar → a próxima execução
   não re-dispara; ShedLock impede execução concorrente entre instâncias; o lock
   pessimista do lojista serializa com saques manuais (o perdedor da corrida faz
   skip/409 sem duplicar).

8. **Falha síncrona do PSP** (Fase B): mesmo fluxo do saque manual —
   `WithdrawalSettlementService.settleFailedByWithdrawalId` (estorno do débito +
   ledger `WITHDRAWAL_REVERSAL` + saque `FAILED`) e o erro é logado; **nunca**
   derruba o job. Falha **pós-aceite** do PSP (webhook `FAILED`/reconciliação)
   também usa o pipeline existente — **nenhuma duplicação de estorno** (B-04).

9. **Conclusão**: `COMPLETED` via webhook de saída/reconciliação (Spec 36) —
   reusa todo o pipeline, incluindo o evento `WITHDRAWAL_COMPLETED` no outbox →
   webhook de saída (Spec 35). Atividade `AUTO_WITHDRAW_REQUESTED` (actor
   `SYSTEM`, sem IP) registrada na Fase B com o valor. Payout `FAILED` →
   saldo restaurado acima do limiar → próxima execução **re-tenta** (auto-retry
   natural, sem ação manual).

10. **Limite por execução**: `foxcash.auto-withdraw.max-withdrawals-per-run`
    (default 100) limita o número de **saques criados** (Fase A OK) por execução
    do job — proteção de pool/PSP; os restantes ficam para a próxima execução.
    Skips (critério 6) não contam.

11. **Frontend do portal**: card "Saque automático" na tela de Saque
    (`portal/src/pages/WithdrawPage.tsx`): toggle liga/desliga, campo de limiar
    (pré-preenchido com o default do GET), seletor de destino (apenas ATIVOS,
    preselecionado com o salvo), botão salvar → PUT com feedback
    sucesso/erro; mensagem explicativa ("quando o saldo atingir R$ X, sacamos
    automaticamente todo o valor para o destino escolhido"). Só habilitável com
    `≥ 1` destino ativo. Nunca exibe a chave Pix em claro (Spec 31).

12. **Sem quebra de contrato**: nenhuma rota existente muda de shape (mudanças
    aditivas: tabela nova, endpoints novos, enums novos). `mvn test` verde
    (suíte completa + testes novos); E2E novo cenário de auto-saque (configuração
    via API + gatilho por saldo + conclusão via webhook de saída) com cron curto
    no perfil de teste e polling.

## Fora de escopo

- **Gatilho por volume de vendas** — decisão do usuário: o gatilho é o **saldo
  disponível**, não o volume aprovado acumulado.
- **Saque parcial/percentual** ("sacar só o excedente" ou "exatamente o limiar")
  — decisão do usuário: saca **todo o saldo disponível**.
- **Configuração pelo master/administrativa** — é opção do vendedor; o master
  continua com o saque administrativo (Spec 32) e os controles de bloqueio
  existentes (que o auto-saque respeita).
- **Notificação dedicada (e-mail/push) do saque automático** — o fluxo de
  outbox/webhook existente (Spec 35) já notifica o bot/lojista
  (`WITHDRAWAL_COMPLETED`).
- **Agendamento por horário fixo** (ex.: "sacar todo dia 15") — fora; o gatilho
  é exclusivamente o cruzamento do limiar de saldo.
- **Agendador/worker externo** — o job roda in-process com ShedLock (padrão Spec
  53); worker separado permanece adiado.
