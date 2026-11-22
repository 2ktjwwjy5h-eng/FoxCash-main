# Revisão de Segurança — TrevoPay (2026-08-11)

> Auditoria de segurança transversal exigindo raciocínio sobre **vetores de
> ataque sutis, race conditions e timing attacks** em todos os subsistemas.
> 29 achados (SEC-01..SEC-29) corrigidos, cobertos por testes e commitados.
> Suíte completa: **1252/0/0, BUILD SUCCESS**.

---

## 1. Escopo

Vetores analisados (com foco em subtileza — não nos controles já existentes):

| Vetor | Achados |
|---|---|
| Race conditions (lost update, TOCTOU, corridas de escrita) | SEC-01, SEC-10, SEC-11, SEC-14, SEC-15 |
| Timing attacks (oráculos de resposta/tempo) | SEC-04, SEC-05 |
| Bypass de rate limit (cardinalidade, path traversal) | SEC-02, SEC-16 |
| IDOR / autorização por recurso (defesa em profundidade) | SEC-13 |
| Amplificação de recursos (memória, pool de conexões) | SEC-09, SEC-12 |

Análise realizada em sete rodadas: a **rodada 1** corrigiu os 10 achados
iniciais (SEC-01/02/04/05/09/10/11/12/13/14); a **rodada 2** auditou essas
correções e encontrou mais 4 falhas reais que os testes verdes não cobriam
(SEC-15, SEC-16, SEC-04 incompleto, SEC-13 estendido); a **rodada 3** auditou
as áreas não cobertas (SEC-17, SEC-18); a **rodada 4** os fluxos de
administração (SEC-19); a **rodada 5** o MFA/recovery codes, a saída de
webhooks (SSRF) e o rate limit por principal (SEC-20..SEC-23); a **rodada 6**
o ciclo de vida de credenciais (rotação/revogação), a cripto/entropia e os
fluxos de cobrança/chargeback (SEC-24..SEC-26); a **rodada 7** a lógica de
negócio (IDOR, entrega/estorno idempotentes, plano sem pagamento) e as
fronteiras entre subsistemas (API × portal × bots × webhooks) — SEC-27..SEC-29.

---

## 2. Achados da rodada 1

### SEC-01 — Lost update no lockout (master, lojista, TOTP) — `corrigido`
**Vetor:** o incremento de `failed_login_attempts` era read-modify-write na
entidade em memória do request + `save`: N requisições paralelas liam o mesmo
contador e sobrescreviam umas às outras — brute-force paralelo burlava o
lockout (nunca atingia o limite).
**Correção:** persisters (`MasterLoginLockoutPersister`,
`MerchantLoginLockoutPersister`, `MasterTotpFailurePersister`) re-leem a linha
com `findByIdForUpdate`/`findByMerchantIdForUpdate` (PESSIMISTIC_WRITE) em
transação própria (REQUIRES_NEW) e re-aplicam a falha sobre o estado FRESCO.
Se outra requisição concorrente já disparou o lockout, a tentativa não é
contada (a janela não é estendida).

### SEC-02 — Bypass de rate limit por cardinalidade de path — `corrigido`
**Vetor:** a chave do bucket era `requestURI|IP` (ou `path|principal`): rotas
com `{id}` variável (`/transactions/{id}`, `/billing/invoices/{id}`, ...)
criavam um bucket NOVO a cada id — um cliente alternando ids contornava o teto
por completo.
**Correção:** nova classe `RateLimitKey.normalize` colapsa o path na rota
registrada (prefixo fixo) — o bucket reflete a intenção do controle (limite
por IP/principal na rota), não por instância de recurso.

### SEC-04 — Oráculo de timing no lockout (BCrypt depois do check) — `corrigido`
**Vetor:** o check de lockout rodava ANTES do BCrypt — conta bloqueada
respondia ~1ms, senha incorreta ~100ms (BCrypt). O atacante media o tempo para
detectar o fim da janela e retomar o brute-force.
**Correção:** BCrypt roda PRIMEIRO (master e lojista) — conta bloqueada e senha
incorreta respondem no MESMO tempo (~BCrypt). Credencial ausente do lojista
também executa o BCrypt dummy (sem oráculo de existência).

### SEC-05 — Bypass de escopo de API key por path traversal — `corrigido`
**Vetor:** o escopo da rota era decidido sobre o path CRU — uma URI com `..`
(ex.: `/api/v1/nyx/pix/../transactions`) era avaliada com o path cru e podia
receber escopo errado (bypass de escopo).
**Correção:** `ApiKeyAuthFilter.requiredScope` normaliza o path com
`StringUtils.cleanPath` (colapsa `..`, `.` e `//` duplicadas) — o escopo é
decidido na MESMA forma que o container/router enxerga a rota.

### SEC-09 — Amplificação de memória no logging de resposta — `corrigido`
**Vetor:** o `ContentCachingResponseWrapper` (sem limite) bufferizava a
resposta INTEIRA em memória em TODOS os requests (mesmo sem DEBUG) — ampliação
de memória sob carga.
**Correção:** o wrapper só é usado com DEBUG ativo (`log.isDebugEnabled()`);
sem DEBUG a resposta flui em stream direto (sem buffer de 2x).

### SEC-10 — TOCTOU no single-use do token de reset — `corrigido`
**Vetor:** o reset lia o token, validava e fazia `save` — dois reset
concorrentes com o MESMO token ambos passavam (leitura + save, sem atomicidade).
**Correção:** `consumeIfOpen(tokenHash, now)` — UPDATE condicional
(`used_at IS NULL AND expires_at > now`); apenas o primeiro consome, o segundo
responde o mesmo 400 (single-use de verdade, sem TOCTOU).

### SEC-11 — Lost update no incremento de tentativas do outbox — `corrigido`
**Vetor:** `incrementAttempts` era `findById` + `set` + `save` — processadores
concorrentes do mesmo evento sobrescreviam o contador (retry ilimitado).
**Correção:** incremento ATÔMICO no banco (`incrementAttemptsAtomically`,
UPDATE) + leitura fresca após o incremento.

### SEC-12 — I/O de rede do PSP dentro de transação — `corrigido`
**Vetor:** o `reconcile` do `WithdrawalReconciliationService` era
`@Transactional` — a latência do PSP segurava conexão do pool e linhas da
tabela de saques por segundos dentro da transação do job.
**Correção:** método sem `@Transactional` (I/O fora da transação); cada
liquidação/estorno mantém transação própria (`settleByExternalReference`
`@Transactional`; retry via `WithdrawalSubmissionPersister` REQUIRES_NEW).

### SEC-13 — IDOR latente no create charge — `corrigido`
**Vetor:** o serviço confiava cegamente no `merchantId` do comando — o fallback
`findById` carregava o lojista alheio (IDOR latente; não alcançável pelas rotas
de hoje, mas o serviço não podia confiar no comando).
**Correção:** se o principal autenticado é um LOJISTA (resolver preenchido) mas
o `merchantId` do comando aponta para OUTRO lojista → falha fechada (403).

### SEC-14 — Lost update na fatura sob coleta concorrente — `corrigido`
**Vetor:** o collect usava a snapshot da fatura carregada pelo chamador — uma
coleta concorrente da MESMA fatura (job diário + manual do master) reduzia o
outstanding e o débito era calculado sobre o valor antigo.
**Correção:** re-leitura FRESCA da fatura APÓS adquirir o lock do merchant (o
ponto único de mutação do saldo/fatura); se já quitada → no-op.

---

## 3. Achados da rodada 2 (auditoria da rodada 1)

### SEC-15 — Race sucesso × lockout (lost update inverso) — `corrigido`
**Vetor (novo):** o SEC-01 tornou o incremento de FALHA atômico, mas o caminho
de SUCESSO ainda salvava a snapshot stale: um login válido cujo load precedeu a
aplicação do lockout por uma falha CONCORRENTE gravava contador/lockedUntil
zerados por cima do lockout recém-aplicado — burlava a janela pelo lado do
sucesso.
**Correção:** o login válido (master e lojista) relê com
`findByIdForUpdate`/`findByMerchantIdForUpdate` ANTES de limpar o contador — se
o estado FRESCO está bloqueado, responde 401 (o lockout prevalece).

### SEC-16 — Bypass de rate limit por path traversal (`..`) — `corrigido`
**Vetor (novo):** `RateLimitFilter`/`AuthenticatedRateLimitFilter` usavam o
path CRU na chave e na decisão de teto — `/api/v1/transactions/../auth/master/
login` resolvia para o login real (teto padrão baixo) mas caía no grupo
TRANSACIONAL (teto alto 300/min) → bypass do rate limit de login por `..`.
**Correção:** `StringUtils.cleanPath` ANTES da chave e da decisão de teto em
ambos os filtros (mesmo padrão do SEC-05). O `shouldNotFilter` do
authenticated também normaliza (`/api/v1/../actuator` não é tratado como rota
da API).

### SEC-04 (incompleto) — Oráculo de timing de conta inativa — `corrigido`
**Vetor (novo):** no master, `!master.isActive() || !passwordHasher.matches(...)`
fazia short-circuit `||` — conta INATIVA pulava o BCrypt (~1ms) vs senha errada
(~100ms): oráculo de ativação por timing.
**Correção:** o `matches` é avaliado em variável ANTES do `if` — o BCrypt
sempre roda, conta inativa e senha incorreta respondem no mesmo tempo.

### SEC-13 (estendido) — IDOR latente em services irmãos — `corrigido`
**Vetor (novo):** `CreatePixTransactionService` e `WithdrawalService` tinham o
mesmo padrão do `CreatePixChargeService` (confiança cega no `merchantId` do
comando) — o SEC-13 tinha corrigido apenas o charge.
**Correção:** mesmo padrão aplicado aos dois (resolver checa principal lojista
vs `merchantId` do comando → 403 fechado); fora de request HTTP (jobs/admin) o
resolver fica vazio e o fallback ao repositório é preservado.

---

## 4. Achados da rodada 3 (auditoria das áreas não cobertas)

### SEC-17 — Bypass da verificação HMAC do webhook por path traversal — `corrigido`
**Vetor (novo, CRÍTICO):** o `WebhookSignatureFilter` decidia
`shouldNotFilter`/config/rate-limit com o path **CRU**. Uma URI
`/webhooks/../webhooks/pix` é normalizada pelo container para `/webhooks/pix` —
o Spring Security libera (`permitAll`) e o controller processa — mas o filtro
via o path cru (`≠ /webhooks/pix`) → **a verificação de assinatura era pulada**:
qualquer requester podia enviar `PAID` sem o segredo do PSP e creditar o wallet.
Mesmo vetor do SEC-05/16, mas no componente que move dinheiro (a rodada 1
normalizou o escopo e o rate limit, mas não o filtro de assinatura).
**Correção:** `StringUtils.cleanPath` em TODAS as decisões do filtro
(`shouldNotFilter`, `configFor`, chave do rate limit, logs). Testes: path com
`..` continua sendo filtrado, exige assinatura válida (401 sem / 200 com) e
compartilha o bucket do rate limit com o path real.

### SEC-18 — Body inspection inspecionando webhook excluído — `corrigido`
**Vetor (novo, MÉDIO):** o `RequestBodyInspectionFilter.shouldNotFilter` usava o
path **cru** no casamento de exclusão — `/webhooks/../webhooks/pix` não casava
com o excluded `/webhooks/...` e o filtro inspecionava o payload do PSP
(reentrega legítima com texto livre podia disparar falso positivo de SQLi →
403 → disponibilidade do webhook sacrificada — violaria o critério 6 da Spec 63).
**Correção:** `StringUtils.cleanPath` no casamento de exclusão (o path
normalizado casa com o excluded e o webhook segue excluído).

---

## 4b. Achados da rodada 4 (auditoria dos fluxos de administração)

### SEC-19 — Save de merchant sem lock sobrescreve movimentação — `corrigido`
**Vetor (novo, ALTO):** `MasterWithdrawalControlService` (block/release),
`MerchantStatusControlService` (suspend/release) e
`RiskMonitoringService.maybeAutoSuspend` carregavam o merchant com
`findById` **sem** FOR UPDATE e salvavam. O save do JPA grava TODOS os campos
da entidade (sem `@DynamicUpdate`), incluindo `wallet_balance` — um
débito/crédito **concorrente** (saque/webhook/chargeback, que usam
`findByIdForUpdate`) seria sobrescrito pela snapshot stale: bloqueio/suspensão
revertia silenciosamente uma movimentação financeira (perda de crédito ou
restauração de débito). Mesma classe do SEC-15, mas no caminho de
administração que não foi auditado nas rodadas 1-3.
**Correção:** `findByIdForUpdate` antes de salvar nos 3 serviços (lock
pessimista serializa com os fluxos de dinheiro); o auto-suspend do risk
relê o estado fresco sob lock **apenas** quando vai suspender (evita lock em
todos os lojistas do batch). Testes: cada serviço preserva o `wallet_balance`
fresco no save.

---

## 4c. Achados da rodada 5 (auditoria do MFA, da saída de webhooks e do rate limit por principal)

### SEC-20 — TOCTOU no single-use do recovery code do MFA — `corrigido`
**Vetor (novo, ALTO):** o consumo do recovery code
(`MasterMfaService.verifySecondFactor`) era leitura
(`findUnusedByMethodIdAndCodeHash`) + `use(now)` + `saveAll` — N logins
concorrentes com o MESMO código liam UNUSED e ambos passavam: o single-use era
burlado sob corrida (dois logins válidos com UMA posse — o 2º fator de
emergência do master). Mesma classe do SEC-10 (consumo não-atômico do token de
reset, corrigido com UPDATE condicional) — mas o recovery code tinha ficado de
fora da correção.
**Correção:** `consumeIfOpen(methodId, codeHash, now)` — UPDATE condicional
(`used_at IS NULL`) no banco; apenas o primeiro login consome, o segundo cai no
caminho do TOTP e falha com o MESMO 401 (sem oráculo). Testes: consume atômico
verificado (nunca leitura+save); código já consumido não autentica.

### SEC-21 — SSRF por DNS rebinding na entrega de webhooks de saída — `corrigido`
**Vetor (novo, ALTO):** a proteção SSRF do outbound webhook validava a URL
(`WebhookUrlValidator` resolve o DNS e rejeita endereços internos) e DEPOIS o
cliente HTTP (`java.net.http`) re-resolvia o hostname NA CONEXÃO — duas
resoluções independentes. Um domínio do atacante podia responder IP público na
validação e IP interno no connect (DNS rebinding — ex.: metadata cloud
`169.254.169.254`): o servidor virava proxy SSRF e o `/test` do webhook
devolvia o status/erro da conexão (oráculo de varredura interna). A docstring
afirmava proteção contra "troca de resolução" que na prática não existia.
**Correção:** transporte trocado para Apache HttpClient 5 com
`WebhookDnsResolver` PINADO — os endereços validados na resolução são
EXATAMENTE os usados na conexão (o hostname original é preservado no Host
header/SNI); redirects desabilitados (um 302 para host interno não passaria
pela validação); corpo de resposta lido com teto (receptor malicioso não
infla memória). Testes: resolver bloqueia loopback/10.x/169.254.x/192.168.x/
CGNAT e IPv4-mapped IPv6; entrega local (allowLoopback=true) continua
funcionando.

### SEC-22 — Estorno de falha síncrona do saque sem lock (TOCTOU) — `corrigido`
**Vetor (novo, ALTO):** `WithdrawalSettlementService.settleFailedByWithdrawalId`
(estorno quando o `createPayout` do PSP lança) carregava o saque com
`findById` SEM FOR UPDATE — o webhook/reconciliação usam
`findByExternalReferenceForUpdate`, mas esse caminho não. Se o PSP aceitou o
payout mas a chamada lançou (timeout pós-aceite) e o webhook `PAID` chega em
paralelo: ambos leem PROCESSING → um grava FAILED+estorno e o outro grava
COMPLETED por cima — o saque conclui E o débito é estornado (efeito financeiro
duplicado: o payout sai e o valor volta ao saldo). Mesma classe do SEC-15/19
(leitura sem lock antes do save), no caminho de liquidação.
**Correção:** `findByIdForUpdate` (lock pessimista) — serializa com o
webhook/reconciliação; o gate de status opera sobre o estado fresco. Testes:
o estorno usa o lock e reverte saldo+ledger corretamente.

### SEC-23 — Bypass do rate limit por cardinalidade nas rotas de recursos — `corrigido`
**Vetor (novo, MÉDIO):** o `RateLimitKey` (SEC-02) colapsava apenas as rotas
transacionais/dashboard (`/transactions`, `/billing`, `/nyx/deliveries`,
`/nyx/plans`, `/portal/dashboard`) — as rotas de RECURSOS do lojista
(`/api/v1/merchants/{id}/...`, `/api/v1/portal/plans/{id}`,
`/api/v1/portal/webhooks/{id}`, `/api/v1/portal/store/*/{id}`,
`/api/v1/portal/sales/{id}`, ...) ficaram com `{id}` SEM colapso: um lojista
criava N recursos (plans/webhooks/stores/... do próprio id) e ganhava N
buckets do limite por PRINCIPAL (Spec 58) → vazão N × teto — o objetivo de
limitar o LOJISTA era burlado por cardinalidade (mesma classe do SEC-02, que
tinha corrigido só parte das rotas).
**Correção:** prefixos de recursos adicionados ao `VARIABLE_PREFIXES`
(`/api/v1/merchants`, `/api/v1/portal/plans|store|webhooks|sales|credentials|destinations|charges`,
`/api/v1/nyx/scheduled-messages`). Testes: ids distintos da mesma rota caem no
MESMO bucket do principal (429 no teto); a chave do store usa o prefixo fixo.

---

## 4d. Achados da rodada 6 (auditoria do ciclo de vida de credenciais, da cripto e dos fluxos de cobrança/chargeback)

### SEC-24 — Revogação de API key não revoga a credencial legada do cadastro — `corrigido`
**Vetor (novo, ALTO):** o `ApiKeyAuthFilter` tem um **fallback legado** que
autentica pela coluna `merchants.api_key_hash` (Spec 12) com **escopo total
implícito** — mantido para integrações anteriores à Spec 24. A migração V16
backfillou TODAS as chaves legadas para `merchant_api_keys` (label "Chave do
cadastro") e o `RegisterMerchantService` grava o MESMO hash nas DUAS tabelas —
mas `ApiKeyManagementService.revoke` marcava apenas a linha gerenciável como
`REVOKED`, **nunca limpava `merchants.api_key_hash`**. Resultado: a revogação
era **reversível** — se a linha gerenciável fosse removida (feature futura de
delete de chave, limpeza de dados, edição manual no banco), a chave "revogada"
**ressuscitava** pelo fallback com escopo total, ignorando os escopos da Spec
24. A coluna legada ficava como uma credencial dormente, irrevogável e
fora do controle do portal.
**Correção:** `revoke` chama `MerchantRepository.clearLegacyApiKeyHashIfMatches`
— UPDATE condicional (`api_key_hash = NULL WHERE id = ? AND api_key_hash = ?`);
só limpa quando o hash legado é o MESMO da chave revogada (a coluna guarda
apenas o hash do cadastro — revogar uma chave escopada não toca a coluna de
outra). O fallback legado permanece como ponte para linhas pré-V16, mas a
revogação agora é REAL. Testes: o revoke limpa o hash legado com o hash da
chave; revoke de chave inexistente não toca a coluna.

### SEC-25 — Ciclo de vida da credencial do master quebrado (senha imutável, 64 bits, sem revogação de sessões) — `corrigido`
**Vetor (novo, ALTO):** o master — operador privilegiado da plataforma (Spec 41)
— tinha a senha **gerada pelo sistema, exibida UMA vez no create e imutável**:
nenhum fluxo de troca/reset/rotação existia (`Master` sem `changePassword`,
`MasterSessionRepository` sem `revokeAllByMasterId`, controllers sem endpoint).
A senha gerada tinha apenas **64 bits** (8 bytes hex, `mst_<16 hex>`) — abaixo
do padrão para credencial de alto valor. Consequências: (1) se a senha exibida
uma vez vazasse (log do request, histórico de terminal, anotação), **não havia
rotação possível** — comprometimento permanente de um admin com acesso a
todos os lojistas e perfis; (2) nenhum evento de credencial revogava sessões —
um token de sessão roubado continuava válido até expirar mesmo após qualquer
ação de remediação (a desativação do perfil até bloqueia, mas mata a conta — o
rotacionar a senha não existia como alternativa).
**Correção:** (a) `MasterPasswordGeneratorImpl` gera 128 bits (`mst_<32 hex>` —
hashes existentes continuam válidos, o formato só muda para novas senhas);
(b) `Master.changePassword(hash)` no domínio; (c)
`MasterSessionRepository.revokeAllByMasterId` (UPDATE condicional
`revoked_at IS NULL`, mesmo padrão do reset do lojista Spec 40);
(d) `POST /api/v1/auth/master/change-password` — exige a senha ATUAL (BCrypt
**sempre** roda — conta inativa e senha incorreta respondem no mesmo tempo,
padrão SEC-04), aplica `PasswordPolicy` e **revoga TODAS as sessões** do master
(a rotação mata tokens roubados no instante da troca; re-login obrigatório).
Rota protegida por `ROLE_MASTER` (regra explícita no SecurityConfig) e o evento
`MASTER_PASSWORD_CHANGED` vai para o relatório de atividades (Spec 41). Testes:
troca atualiza o hash + revoga sessões + audita; senha atual errada → 401 sem
efeito; senha fraca rejeitada antes de qualquer mutação; conta inativa ainda
roda o BCrypt; domínio rejeita hash nulo; gerador com 128 bits.

### SEC-26 — TOCTOU no encerramento da PENDING do charge (snapshot stale sobrescreve APPROVED concorrente) — `corrigido`
**Vetor (novo, ALTO):** `PixChargePersistence.failPending`/`cancelPending`
recebiam a **snapshot** da PENDING (carregada ANTES da chamada ao PSP) e
salvavam por cima — sem lock e sem gate de estado. Cenário: o PSP **criou** a
cobrança mas a resposta ao serviço foi erro/timeout (ex.: 5xx pós-criação,
timeout com aceite), e o webhook `PAID` chegou em paralelo —
`ProcessPixWebhookService` (com `findByExternalReferenceForUpdate`) moveu a
transação para `APPROVED` e creditou o wallet; em seguida o `failPending` do
serviço gravava a snapshot stale (PENDING em memória) como `FAILED` — **a venda
paga era marcada morta** (transação FAILED + retry da idempotencyKey → 409; o
crédito do wallet permanece e a venda "some" do portal). Mesma classe do SEC-22
(estorno do saque com snapshot stale), mas no lado da COBRANÇA — o SEC-22 tinha
corrigido `settleFailedByWithdrawalId`, e `attachAndPublish` já tinha lock
(Spec 62), mas os dois caminhos de encerramento ficaram de fora.
**Correção:** `settlePending(pending, asFailed)` — re-leitura com
`findByIdForUpdate` + gate: encerra SOMENTE `PENDING` **sem**
`external_reference` (nada anexado — o PSP não devolveu cobrança); caso
contrário no-op (o desfecho fica para o webhook/reconciliação). Um `failPending`
não pode sobrescrever um `APPROVED` concorrente, e um `cancelPending` não pode
marcar `REJECTED` uma cobrança que o comprador ainda pode pagar (attach já
anexado). Testes: FAILED/REJECTED não sobrescrevem APPROVED concorrente; não
rejeitam cobrança com external_reference; marcam FAILED/REJECTED somente a
PENDING fresca sem referência (comportamento original preservado).

---

## 4e. Achados da rodada 7 (auditoria da lógica de negócio e das fronteiras entre subsistemas)

A rodada 7 testou os vetores de NEGÓCIO explicitamente pedidos — (a) um lojista
acessar dados de outro, (b) um plano comprado sem pagamento, (c) uma entrega
concluída duas vezes, (d) um saque estornado duas vezes — e as FRONTEIRAS
entre os subsistemas (API × portal × bots × webhooks). Os quatro vetores de
negócio foram **verificados e considerados OK** (detalhes na seção 7); a
auditoria das fronteiras e dos fluxos adjacentes encontrou 3 achados novos:

### SEC-27 — Note da conclusão de entrega sem teto de tamanho (500 por DataException) — `corrigido`
**Vetor (novo, MÉDIO):** o `DeliveryCompleteRequest.note` (bot confirma
`DONE|FAILED` da ação de entrega, Spec 49) NÃO tinha `@Size` — mas a coluna
`plan_delivery_actions_exec.note` é `VARCHAR(255)`. Um `note` acima de 255
caracteres virava `DataException` → **500** na rota do bot (`POST
/nyx/deliveries/{id}/actions/{seq}/complete`). O `DeliveryAccessRequest`
(`/access`) já tinha `@Size(max = 255)` — inconsistência de contrato na MESMA
fronteira (bot × API).
**Correção:** `@Size(max = 255)` no `DeliveryCompleteRequest.note` (400
`validation_error` consistente com o `/access`). Testes de validação: note > 255
rejeitado; exatamente 255 aceito; null aceito (note é opcional).

### SEC-28 — Motivo de falha do saque sem teto quebra o estorno (débito pendurado) — `corrigido`
**Vetor (novo, MÉDIO):** o estorno da falha síncrona do PSP monta
`"Falha no PSP: " + e.getMessage()` — e a coluna `withdrawals.failure_reason`
é `VARCHAR(255)`. Uma mensagem longa do provedor (Pagar.me devolve JSON de erro
grande) virava `DataException` **DENTRO do caminho de estorno**
(`settleFailedByWithdrawalId`): o saque ficava `PROCESSING` com o **débito
pendurado**, o `throw e` original era substituído pela exceção do estorno e a
reconciliação re-tentava o `createPayout` em loop (falha → estorno → falha).
Disponibilidade do fluxo de saque + retenção indevida do saldo.
**Correção:** truncamento a 255 caracteres no **domínio** (`Withdrawal.fail`)
— todo caller (estorno síncrono, webhook, reconciliação) passa por ali; o
estorno nunca quebra por motivo longo. Testes: domínio trunca (e preserva
motivo curto); o settlement com reason de 500 caracteres conclui o estorno.

### SEC-29 — Estorno do saque administrativo não reverte o ADMIN_RECOVERY (receita fantasma) — `corrigido`
**Vetor (novo, MÉDIO-ALTO):** o saque administrativo (Spec 32) registra
`ADMIN_RECOVERY` no ledger do master (Spec 34) na Fase A. Quando o payout
falha, o estorno (`settleOutcome FAILED`) devolve o valor ao lojista
(`WITHDRAWAL_REVERSAL` no wallet ledger) — mas **não revertia o
`ADMIN_RECOVERY`** do master ledger. O relatório de receita (Spec 34) mostrava
valor recolhido que **não ocorreu** (receita fantasma), e a reconciliação do
wallet (Spec 30) não captura a divergência (ela valida apenas o saldo do
LOJISTA, não o ledger do master). O fluxo de chargeback/refund da venda tinha
a mesma classe de gap coberta pelo `ReceivableRecorder`, mas o caminho do
saque administrativo ficou de fora.
**Correção:** novo tipo `ADMIN_RECOVERY_REVERSAL` gravado no `settleOutcome`
FAILED **somente** para saques `ADMINISTRATIVE` (mesma transação, gate de
status idempotente → reversão única); o `MasterRevenueService.summary` conta a
reversão como perda (subtraída do total/net). Testes: estorno de saque
administrativo grava a reversão; saque do vendedor NÃO toca o ledger do
master; summary subtrai a reversão do net.

---

## 5. Validação

- Suíte completa: **1252 testes, 0 falhas, 0 erros** (rodada 1: 1203 → rodada 2:
  1214 → rodada 3: 1219 → rodada 4: 1222 → rodada 5: 1231 → rodada 6: 1243 →
  rodada 7: 1252, +49 testes novos cobrindo os achados das rodadas 2/3/4/5/6/7).
- `mvn -o clean test` → BUILD SUCCESS (sem classes stale).
- Commits (13, por coesão temática — cada um auto-contido e compilável):

| Commit | Escopo | Arquivos |
|---|---|---|
| `35050a0` | Núcleo de autenticação (SEC-01/04/10/15 + repositórios FOR UPDATE) | 26, +592/−80 |
| `ddf6905` | Edge e serviços transacionais (SEC-02/05/09/11/12/13/14/16) | 20, +520/−47 |
| `6e72a46` | Rodada 3 (SEC-17/18 — path traversal no webhook e body inspection) | 4, +112/−9 |
| `027462d` | Rodada 4 (SEC-19 — save de merchant sem lock sobrescreve movimentação) | 6, +106/−10 |
| `3357c8f` | Rodada 5 (SEC-20/22 — consume atômico do recovery code + estorno do saque com lock) | 10, +119/−23 |
| `a49a7dd` | Rodada 5 (SEC-23 — rate limit por principal: colapso das rotas de recursos) | 2, +71/−1 |
| `bd0083c` | Rodada 5 (SEC-21 — SSRF por DNS rebinding: HttpClient5 com DnsResolver pinado) | 5, +217/−25 |
| `0cdd168` | Rodada 6 (SEC-24 — revoke limpa a credencial legada `merchants.api_key_hash`) | 5, +74/−1 |
| `cbca457` | Rodada 6 (SEC-25 — ciclo de vida da credencial do master: 128 bits + change-password + revokeAllByMasterId) | 14, +239/−5 |
| `35c86a9` | Rodada 6 (SEC-26 — encerramento da PENDING do charge sob lock com gate) | 3, +119/−6 |
| `df36c2d` | Rodada 7 (SEC-27 — note da conclusão de entrega com teto de 255) | 2, +53/−1 |
| `1fee6b4` | Rodada 7 (SEC-28/29 — integridade do estorno do saque: motivo truncado + reversão do ADMIN_RECOVERY) | 7, +182/−4 |

> Nota sobre a divisão: os arquivos com mudanças das duas rodadas num único
> hunk (`MasterAuthService`, `MerchantPortalAuthService`, `RateLimitFilter`,
> `AuthenticatedRateLimitFilter` + testes) não são separáveis por hunk sem
> quebrar a compilação de um commit — a divisão foi por coesão temática.

---

## 6. Lições para auditorias futuras

1. **Testes verdes não provam correção de segurança** — os achados das rodadas
   2 e 3 (SEC-15/16/17/18, SEC-04 incompleto, SEC-13 estendido) existiam com a
   suíte toda passando. Auditar cada correção com o vetor em mente (não só
   rodar testes).
2. **Normalização de path é um contrato transversal** — se um componente
   normaliza (`cleanPath`) e outro usa o path cru, o vetor de `..` reaparece
   onde menos se espera (rate limit, escopo, **assinatura de webhook**,
   inspeção de corpo). O SEC-17 provou o pior caso: um `..` no path de webhook
   pularia a verificação HMAC e permitiria crédito de PAID falso. Auditoria de
   `request.getRequestURI()` deve ser um passo explícito em TODO filtro.
3. **Lost update tem dois lados** — tornar a FALHA atômica sem tornar o SUCESSO
   atômico deixa o caminho inverso aberto (SEC-15: login válido apagando
   lockout). Sempre revisar os dois sentidos da escrita.
4. **Short-circuit `||` é oráculo de timing** — `!cond || expensive()` pula o
   custoso quando `!cond` é true; extrair o `expensive()` para variável antes do
   `if` quando o tempo precisa ser uniforme.
5. **Single-use exige UPDATE condicional, não leitura+save** — o SEC-10 corrigiu
   o token de reset, mas o MESMO padrão sobreviveu no recovery code do MFA
   (SEC-20): sempre que um controle é "one-time", procurar leitura → mutação →
   save e substituir por UPDATE atômico (`used_at IS NULL`). Auditoria de
   single-use deve cobrir TODAS as credenciais, não só as principais.
6. **Validar DNS e conectar com a MESMA resolução** — um cliente HTTP que
   re-resolve o hostname na conexão anula a validação SSRF da entrega
   (SEC-21: DNS rebinding). O cliente deve pinar a resolução validada
   (DnsResolver customizado) e preservar o hostname no Host header/SNI; nunca
   seguir redirects num destino validado por URL.
7. **Cardinalidade: inventariar TODAS as rotas com `{id}`** — o SEC-02 corrigiu
   as rotas transacionais, mas as rotas de RECURSOS do lojista
   (`/merchants/{id}`, `/portal/plans/{id}`, ...) ficaram com bucket por id
   (SEC-23): um usuário com N recursos ganha N × teto. O inventário de rotas
   com segmento variável deve ser um passo explícito no rate limit — e
   re-verificado quando novos controllers nascem.
8. **Revogação tem que matar a credencial em TODAS as representações** — a chave
   gerenciável (Spec 24) e a coluna legada `merchants.api_key_hash` (Spec 12)
   guardam a MESMA credencial (SEC-24): revogar uma representação e deixar a
   outra viva torna a revogação reversível (a "chave revogada" ressuscita com
   escopo total se a linha gerenciável for removida). Ao inventariar
   credenciais, procurar TODAS as colunas/tabelas que autenticam a MESMA posse
   (fallbacks de compatibilidade são os suspeitos naturais) e fazer a
   revogação limpar todas.
9. **Credencial sem rotação é um incidente permanente** — o master tinha senha
   gerada com 64 bits, exibida UMA vez e imutável (SEC-25): um vazamento da
   exibição inicial (log/terminal/anotação) não tinha remediação além de
   desativar a conta. Toda credencial privilegiada exige ciclo de vida: entropia
   adequada (128 bits para credencial de alto valor), troca por senha atual
   (BCrypt sempre roda — timing uniforme) e revogação das sessões no evento de
   rotação (tokens roubados morrem no instante da troca).
10. **"Já tem lock aqui" não cobre o arquivo inteiro** — o SEC-22 corrigiu o
    estorno do saque e o `attachAndPublish` da cobrança tinha lock (Spec 62),
    mas os caminhos de ENCERRAMENTO da PENDING (`failPending`/`cancelPending`)
    continuavam salvando a snapshot stale por cima de um APPROVED concorrente
    (SEC-26): uma venda paga virava FAILED. Ao auditar um fluxo com lock em um
    ponto, listar TODOS os métodos do componente que mutam a mesma linha e
    verificar o gate de estado sob lock em cada um — o "padrão do SEC-22" deve
    ser aplicado a todos os save de snapshot pré-I/O de rede.
11. **Contratos de fronteira: conferir o tamanho de CADA campo livre contra a
    coluna** — os vetores da rodada 7 nasceram de texto livre sem `@Size`
    (`DeliveryCompleteRequest.note` — SEC-27) e de concatenação sem teto
    (`"Falha no PSP: " + e.getMessage()` — SEC-28) contra colunas
    `VARCHAR(255)`: em ambos o efeito era `DataException` no caminho
    transacional (500 na rota do bot / estorno quebrado com débito pendurado).
    Ao auditar uma fronteira (bot × API, PSP × serviço), comparar cada campo de
    entrada com o schema; sanear no DOMÍNIO quando o valor atravessa o fluxo
    (o truncamento no domínio protege todos os callers).
12. **Estorno financeiro deve reverter TODOS os ledgers afetados na Fase A** —
    o saque administrativo registrava `ADMIN_RECOVERY` no ledger do master e o
    débito no ledger do lojista; o estorno revertia apenas o do lojista
    (SEC-29 — receita fantasma na Spec 34, invisível para a reconciliação do
    wallet). Ao auditar um fluxo em fases, listar TODAS as escriturações da
    Fase A e garantir que o desfecho FAILED as reverta na MESMA transação.
13. **Verificar a lógica de negócio mesmo quando os controles técnicos estão
    certos** — os quatro vetores pedidos (IDOR entre lojistas, plano sem
    pagamento, entrega 2×, estorno 2×) foram verificados um a um: todos
    bloqueados por escopo (`findByIdAndMerchantId`/`findByIdAndStoreId` +
    `MerchantAccessGuard`), pela criação da entrega apenas no webhook PAID
    idempotente, e por gates de status sob lock. A rodada 7 não encontrou
    achados nesses quatro — os 3 achados vieram dos fluxos ADJACENTES
    (tamanho de campos e consistência de ledgers). Auditoria de negócio =
    confirmar o que está protegido E caçar o que está perto.

---

## 7. Vetores de negócio verificados na rodada 7 (não-achados)

Os quatro vetores pedidos foram testados explicitamente e considerados OK
(defesa em profundidade + gates de estado + idempotência):

| Vetor | Verificação | Por que está protegido |
|---|---|---|
| Lojista acessa dados de outro | Todos os services do portal/API resolvem por `findByIdAndMerchantId`/`findByIdAndStoreId` + `MerchantAccessGuard.requireMerchantAccess` nos controllers REST; consultas cross-loja respondem 404 sem revelar existência | SEC-13/18 + escopo por principal em TODAS as rotas de recurso (planos, loja, botões, mensagens, webhooks, vendas, saques, cobranças, faturas, destinos, gifts, entregas) |
| Plano comprado sem pagamento | `DeliveryRecorder.recordFor` é chamado APENAS no `ProcessPixWebhookService` (status APPROVED do webhook PAID com HMAC) — nenhuma rota cria entrega sem pagamento; o bot só vê entregas criadas | webhook autenticado + entrega criada na MESMA transação da aprovação |
| Entrega concluída duas vezes | `DeliveryActionCompletionService.complete` usa `findByDeliveryIdOrderBySeqForUpdate` (lock) + gate `PENDING→DONE/FAILED` no domínio — replay vira 204 no-op (1 execução, 1 auditoria) | lock pessimista + máquina de estados do `DeliveryActionExec` |
| Saque estornado duas vezes | `settleOutcome` (compartilhado por webhook/reconciliação/estorno síncrono) roda sob lock (`findByExternalReferenceForUpdate`/`findByIdForUpdate`) com gate `status != PROCESSING → no-op` | SEC-22 + idempotência por estado sob lock |

Fronteiras entre subsistemas verificadas (não-achados): (1) request com
`X-Api-Key` + `Bearer` no MESMO request — o último filtro (`ApiKeyAuthFilter`)
sobrescreve principal E o atributo do merchant de forma consistente (sem mix de
identidades); (2) portal (`ROLE_MERCHANT_PORTAL`) não acessa `/api/v1/**` e o
bot (`ROLE_MERCHANT`) não acessa `/portal/**` (SecurityConfig); (3) webhooks
de entrada com segredos SEPARADOS por rota (pix vs payouts), HMAC sobre o raw
body em tempo constante, rate limit por IP e payload com teto (SEC-17/59).

---

## 8. Rodada 8 — Canais de interação entre subsistemas (verificação, sem achados de código)

Auditoria dos canais de interação NÃO cobertos nas rodadas 1–7. **Nenhum achado
de código novo** — todas as fronteiras confirmadas fechadas (as correções das
rodadas anteriores fecharam as janelas); 4 gaps de CONFIGURAÇÃO de produção
identificados (seção 9).

| Canal | Verificação | Resultado |
|---|---|---|
| Outbox de SAÍDA (`WebhookEventPublisher` → `NotificationProcessor` → `HttpMerchantNotifier`) | Publicação DENTRO da transação de origem; entrega at-least-once com dedup (`X-FoxCash-Event-Id`); retry/backoff exponencial; dead-letter `OUTBOX_DEAD` com anti-cascata; ShedLock + watchdog no scheduler; HMAC por webhook com o segredo do lojista; SSRF fechado (SEC-21: DnsResolver pinado + redirects off + corpo de resposta com teto) | OK |
| Webhook de entrada PAYOUTS (`PayoutWebhookController`) | Assinatura HMAC (segredo próprio da rota); `failedReason` do PSP truncado no domínio (SEC-28); settlement idempotente sob lock | OK |
| Bootstrap (`MasterSeedRunner`, Turnstile, admin key) | Seed do master só via env vars (`NYX_MASTER_SEED_*`, sem default — sem seed → log, sem admin inicial); Turnstile **fail-open** (ADR-010 — disponibilidade; rate limit como backstop) com 4xx = rejeita; admin-api-key com fail-fast no boot em prod | OK (decisões documentadas) |
| Jobs/schedulers (7) | TODOS com `@SchedulerLock` (ShedLock) — sem duplicação entre réplicas; lock curto (PT5M) + watchdog nos dispatchs | OK |
| Frontend (portal SPA) | Token opaco em `sessionStorage` (nunca localStorage); CSP `default-src 'self'` + Cloudflare Turnstile; rotas SPA públicas servem HTML, API protegida por role | OK |
| Sanitização de logs | `SensitiveDataLoggingFilter` redige apiKey/password/token/secret/document/pixKey/CPF/CNPJ/authorization de request/response bodies; `logging.sanitized: true` em prod | OK |
| Token de reset de senha | Em prod: `deliverer=smtp` (default), `expose-reset-token=false`, e `deliverer=log` é **rejeitado no boot** (fail-fast — o token nunca vai para logs) | OK |

---

## 9. Prontidão de produção — veredito e gaps de configuração

O **código está pronto para produção** (29 achados de segurança corrigidos em 8
rodadas, 1252 testes, E2E 105/105, load test 600/s p95 ~120ms, migrations
versionadas, HTTPS obrigatório com fail-fast nos segredos). A prontidão FINAL
depende de 4 configurações por env (nenhuma é bug de código — são defaults
conservadores que exigem decisão explícita do operador):

| # | Gap (configuração) | Impacto se não configurado | Configuração correta |
|---|---|---|---|
| 1 | **Turnstile OFF por default em prod** (`NYX_TURNSTILE_ENABLED:false`) | Sem anti-bot no login/cadastro (só rate limit por IP protege) | `NYX_TURNSTILE_ENABLED=true` + `NYX_TURNSTILE_SITE_KEY`/`NYX_TURNSTILE_SECRET_KEY` |
| 2 | **Rate limit store=memory por default** | Em deploy multi-instância sem Redis, o teto por IP/principal é POR-INSTÂNCIA (N réplicas = N × teto) | `NYX_RATE_LIMIT_STORE=redis` + `SPRING_DATA_REDIS_*` |
| 3 | **SMTP sem fail-fast no boot** | Credenciais SMTP ausentes: forgot-password responde 204 mas o e-mail NÃO sai — recuperação de senha quebrada silenciosamente | `NYX_RESET_TOKEN_SMTP_*` + `NYX_PORTAL_BASE_URL` |
| 4 | **Master seed sem fail-fast** | Sem `NYX_MASTER_SEED_*`, nenhum admin inicial é criado (apenas log) — painel master inacessível até criar manualmente | `NYX_MASTER_SEED_USERNAME`/`NYX_MASTER_SEED_PASSWORD` (rotacionar após o 1º login — SEC-25) |

Decisões aceitas e documentadas (não são gaps): Turnstile **fail-open** em
outage do Cloudflare (ADR-010 — disponibilidade primeiro; o rate limit por IP
fica como backstop); rate limit **fail-open** com store redis (Spec 66); o
`mock` de PSP nunca entra em prod (fail-fast — provider sem default).
