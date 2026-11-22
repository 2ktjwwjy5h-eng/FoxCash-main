# Spec 140 — Design: Rebranding TrevoPay → FoxCash

## Abordagem

Rebrand mecânico e abrangente, executado em fases com **gate de build/teste
entre elas** e **rollback preservado** (branch `rebrand-foxcash` + snapshot dos
arquivos-chave). Renomear é uma operação de baixa inteligência e alta precisão:
o risco está em **perder um vetor** (ex.: classe referenciada no pom, env var no
yml, chave Redis em string). Por isso cada fase termina com um `grep` de
auditoria do vetor renomeado = 0 (exceto histórico).

## Vetores de renomeação (mapeamento exato)

| Vetor | De | Para | Onde |
|---|---|---|---|
| Package Java | `com.nyxcash` | `com.foxcash` | dirs `src/{main,test}/java/com/nyxcash` + `package`/`import` |
| Classes | `Nyx*` | `Fox*` | 6 controllers + 5 testes (`FoxStorefrontController` etc.) |
| Classe principal | `TrevoPayApplication` | `FoxCashApplication` | 1 arquivo + referências (jacoco pom, Dockerfile, docs) |
| pom.xml | `com.nyxcash` / `trevopay-backend` | `com.foxcash` / `foxcash-backend` | groupId/artifactId/name |
| Dockerfile | `trevopay-backend-0.1.0-SNAPSHOT.jar` | `foxcash-backend-0.1.0-SNAPSHOT.jar` | nome do jar |
| Config Spring | `nyxcash:` | `foxcash:` | application*.yml + `@ConfigurationProperties`/`@Value` |
| Env vars | `NYX_*` | `FOXCASH_*` | yml `${NYX_...}` + código + docs |
| Logger | `nyxcash.*` | `foxcash.*` | logback-spring.xml |
| Headers webhook | `X-NyxCash-*` | `X-FoxCash-*` | código + docs (contrato público!) |
| E-mail dummy | `nao-existe@nyxcash.invalid` | `nao-existe@foxcash.invalid` | código BCrypt/anti-enumeração |
| Rotas API bots | `/api/v1/nyx/*` e `/webhooks/nyxcash` | `/api/v1/foxcash/*` e `/webhooks/foxcash` | controllers `Fox*` + bot core |
| Métricas | `nyx_*` | `foxcash_*` | código Micrometer (25) + docs |
| Redis keys | `nyx:*` | `foxcash:*` | código (~12) |
| Jobs ShedLock | `nyxcash-*` | `foxcash-*` | código (12) |
| Nome produto | `TrevoPay`/`trevopay` | `FoxCash`/`foxcash` | docs, UI, e2e, README |
| NPM packages | `nyxcash-portal`/`nyxcash-operator-bot`/`@nyxcash/*` | `foxcash-*` | console/portal/operator-bot |
| TS classes | `NyxCashApiError`/`NyxCashClient` | `FoxCashApiError`/`FoxCashClient` | bots core |
| localStorage | `nyxcash.portal.*`/`nyxcash.console.*` | `foxcash.*` | portal/console |

**Deliberadamente NÃO muda** (infra, não branding): nome/role do banco
`nyxcash`, `DB_URL`/`DB_USERNAME`/`DB_PASSWORD` defaults, `docker-compose`
(`container_name` nyxcash-*), paths de backup `/var/log/nyxcash-backup.log` e
`nyxcash_*.dump` (são operação, fora do código de produto), infra Railway
(`nyxcash-production.up.railway.app`, `nyxcash.railway.internal` — Fase 6).

## Decisões

1. **Branch própria** — `rebrand-foxcash` a partir do main limpo (baseline
   commitado); produção intacta até o merge.
2. **Ordem de execução** — package/classes primeiro (o que mais quebra o build),
   depois build/identidade, config/runtime, docs, frontend. Cada fase com gate.
3. **Fase 6 adiada** — Railway (serviço/domínio/rede/22 env) depende do domínio
   novo; o código fica pronto (env `FOXCASH_*`), a troca no painel é manual.
4. **Banco intocado** — nenhuma migration; tabelas não usam prefixo `nyx_`.

## Arquivos-chave

- `pom.xml`, `Dockerfile`, `railway.json` (se existir)
- `src/main/resources/application.yml`, `application-dev.yml`, `application-prod.yml`, `logback-spring.xml`
- `src/main/java/com/nyxcash/TrevoPayApplication.java`
- `.github/workflows/ci.yml`, `.github/workflows/dependency-check.yml`
- `src/main/java/com/nyxcash/` (949) e `src/test/java/com/nyxcash/` (261)
- `console/src/`, `portal/src/`, `operator-bot/`, `e2e-tests/`
- `src/main/resources/static/` (assets embutidos)

## Riscos e mitigação

| Risco | Mitigação |
|---|---|
| Vetor de renomeação esquecido | `grep` de auditoria por vetor ao fim de cada fase |
| Header público `X-NyxCash-*` muda contrato | Renomeado junto (docs atualizadas); é a intenção do rebrand |
| Env var órfã (código vs yml) | Gate de boot (context-tests) valida binding `foxcash:*` |
| Asset estático embutido com nome antigo | `npm run build` regenera; grep nos assets |
| CI quebra (refs package) | Fase 2 ajusta ci.yml/dependency-check.yml antes do merge |
