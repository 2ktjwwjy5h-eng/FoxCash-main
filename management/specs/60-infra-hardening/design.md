# Spec 60 — Design: hardening de infraestrutura

## Contexto

- `server.tomcat`: sem config → defaults Spring Boot (`max-connections=8192`,
  `accept-count=100`, `connection-timeout` default, sem keep-alive custom).
  8192 conexões em instância ~512MB é convite a DoS de sockets/RAM.
- `hikari.maximum-pool-size=10` (application.yml) com 200 threads Tomcat —
  sob pico, 190 threads esperam no pool → latência explode / timeout.
- Dockerfile: `-XX:MaxRAMPercentage=75` sem `-Xmx` — em Railway ~512MB, heap
  ~384MB; carga real pode OOM (restart loop).

## Decisões

1. **Tomcat explícito** (`server.tomcat` no `application.yml`):
   ```yaml
   server:
     tomcat:
       max-connections: 1000        # default 8192 — anti-DoS de sockets
       accept-count: 200            # fila de aceite (backlog)
       connection-timeout: 10000    # ms — descarta slow-connectors
       keep-alive-timeout: 30000    # ms — ocioso encerra
       max-keep-alive-requests: 100 # recicla conexões, evita conexão presa
   ```
   Valores **baseline** — coerentes com threads (abaixo) e pool; **calibrar no
   benchmark Railway** e ajustar os 3 juntos (interdependentes). Objetivo:
   degradação controlada, não maximizar req/s.
2. **Hikari** (`spring.datasource.hikari.maximum-pool-size: 20`, **baseline**):
   com query média ~5ms, 20 conexões ≈ 4000 req/s de DB > teto atual ~3200 req/s.
   Documentar alerta `hikaricp_connection_timeout_total` no runbook. Calibrar
   no benchmark: pool alto demais estoura o Postgres/threads; baixo demais
   degrada antes do necessário — o alvo é o ponto em que o excesso vira 429
   controlado, não conexão estourando.
3. **JVM** (**baseline**):
   - `Dockerfile`: `ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]`
     (env `JAVA_OPTS` sem rebuild).
   - Railway env: `JAVA_OPTS=-Xmx320m` (**sem cap de metaspace** — achado
     2026-08-08: `-XX:MaxMetaspaceSize=96m` causa `OutOfMemoryError: Metaspace`
     no boot; instância observada ~1GB) — **baseline**;
     ajustar conforme `jvm.memory.used` observado no benchmark (meta: folga sem
     GC thrashing, zero OOM).
   - Se necessário (pico confirmar), reduzir `server.tomcat.threads.max` para
     150 e pool para 15 — coerentemente (ver risco de interdependência).
4. **Sem mudança de contrato**: tudo runtime/config — `mvn test` + E2E devem
   permanecer verdes (validação no próprio CI + smoke).

## Arquivos

| Arquivo | Ação |
|---|---|
| `src/main/resources/application.yml` | `server.tomcat.*` + `hikari.maximum-pool-size` |
| `Dockerfile` | `ENTRYPOINT` com `$JAVA_OPTS` |
| `deploy/railway.md` | documentar `JAVA_OPTS` + alertas (Hikari/GC/memória) |
| teste de carga (k6) | validar 1000/5000/10000 VUs na instância Railway |
