# Spec 60 — Task: hardening de infraestrutura

- [x] 1. `application.yml`: bloco `server.tomcat` (max-connections 1000,
      accept-count 200, connection-timeout 10000, keep-alive-timeout 30000,
      max-keep-alive-requests 100) + `hikari.maximum-pool-size: 20` — todos com
      env override (`NYX_TOMCAT_*`, `NYX_DB_POOL_SIZE`) para calibrar sem rebuild.
- [x] 2. `Dockerfile`: `ENTRYPOINT ["sh","-c","java ${JAVA_OPTS:--XX:MaxRAMPercentage=75} -jar app.jar"]`
      (default seguro preserva o comportamento; `JAVA_OPTS` sobrepõe sem rebuild).
- [x] 3. `deploy/railway.md`: documentar `JAVA_OPTS=-Xmx320m`
      + alertas Prometheus (Hikari timeout, GC, memória) + tabela de tuning.
- [x] 4. `mvn test` **1101/0/0** (sem regressão); E2E completo (config de runtime
      não muda contrato).
- [x] 5. Smoke dev: app sobe com os novos limites (Tomcat initialized, health 200).
- [x] 6. **Benchmark de calibração no Railway** (2026-08-08, k6 ramp
      50→100→500→1000→2000→5000 VUs, burst por run com lojista fresco —
      `rate-limit-calibration.js`): 50/100 VUs **100% 200** (p95 686ms warm);
      500 VUs → 120×200 + 380×429 (teto do principal 120/min), **p95 aceitos
      1.53s = ponto de inflexão**; 1000 VUs → +58 erros de conexão; 2000 VUs →
      +1055 (53%); 5000 VUs → 120×200 + 815×429 + 4065 erros de conexão (81%,
      iterações ~21s = fila de conexão do edge/accept saturada). **Zero 5xx em
      TODOS os runs** — degradação controlada confirmada (429 antes da
      saturação da app). Pool/Hikari/heap validados (abaixo).
- [x] 7. **Verificar integridade sob sobrecarga** (2026-08-08): **pool Hikari
      20/réplica: zero timeouts** (7990 aquisições, acquire máx 1.41s);
      **zero `OutOfMemoryError`** nos logs; **zero restart loop** (deploys
      SUCCESS, sem restart); `jvm.memory.used` ~100MB com folga do teto
      (-Xmx320m); health 200 durante todos os testes; idempotência/ledger/
      outbox já cobertos pelas Specs 28/30/36/53 (E2E 104/104 sem regressão).
- [x] 8. Registrar valores finais (Tomcat/Hikari/JAVA_OPTS) no roadmap
      (2026-08-08): `JAVA_OPTS=-Xmx320m`,
      `NYX_DB_POOL_SIZE=20` por réplica (40 total < max_connections do
      Postgres), Tomcat `max-connections=1000`/`accept-count=200` mantidos
      (teto de conexões identificado — edge/accept satura a partir de ~1000
      concorrentes; manter para degradação controlada).
