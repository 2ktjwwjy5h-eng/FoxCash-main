// @ts-check
const { test, expect } = require('@playwright/test');
const { Client } = require('pg');

/**
 * Sweep zero-balance do saldo do PSP (Spec 92) — E2E:
 * o job transfere o excedente (Disponível − buffer) da conta master para a
 * conta blindada corporativa, com UUID determinístico por dia (replay
 * idempotente) e registra a execução em `job_runs` (Spec 53).
 *
 * A trilha do sweep NÃO é a tabela `withdrawals` (operação de tesouraria —
 * fora do domínio de saque): a verificação é `job_runs` (status SUCCESS) +
 * a estabilidade do `sweepId` (coberta nos unitários do PspSweepService).
 *
 * Requer a app rodando com o perfil dev (cron CURTO do sweep — a cada 30s) e
 * o PostgreSQL acessível (global-setup limpa o banco antes). Para este cenário
 * a app DEVE subir com o sweep ligado e saldo fake alto:
 *
 *   FOXCASH_PSP_SWEEP_ENABLED=true \
 *   FOXCASH_PSP_SWEEP_DESTINATION_KEY=evp-e2e-sweep \
 *   FOXCASH_PSP_MOCK_BALANCE=5000 \
 *   FOXCASH_PSP_SWEEP_BUFFER_AMOUNT=200 \
 *   SPRING_PROFILES_ACTIVE=dev mvn spring-boot:run
 *
 * Sem essas envs o cenário é ignorado (o bean do scheduler só existe com o
 * kill-switch ligado — fail-fast honesto, não um skip silencioso do teste).
 */

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

function dbClient() {
  return new Client({
    host: process.env.DB_HOST || 'localhost',
    port: Number(process.env.DB_PORT || 5432),
    user: process.env.DB_USERNAME || 'nyxcash',
    password: process.env.DB_PASSWORD || 'nyxcash',
    database: process.env.DB_NAME || 'nyxcash',
  });
}

test.describe('Sweep do saldo do PSP (Spec 92)', () => {
  test.describe.configure({ mode: 'serial' });

  test('1. Job de sweep roda, transfere o excedente e grava SUCCESS em job_runs', async () => {
    if (process.env.FOXCASH_PSP_SWEEP_ENABLED !== 'true') {
      test.skip(true, 'requer FOXCASH_PSP_SWEEP_ENABLED=true (kill-switch do sweep)');
    }
    test.setTimeout(240_000);

    // Polling tolerante a timing: o job (cron */30 no perfil dev + ShedLock
    // lockAtLeastFor PT1M) roda a cada ~60-90s; deadline de 180s cobre 2
    // cadências — evita raça (cadência ≈ deadline de 90s era flaky).
    // Assertion: uma execução SUCCESS do job com o nome do scheduler.
    const client = dbClient();
    await client.connect();
    let runs = [];
    const deadline = Date.now() + 180_000;
    while (Date.now() < deadline) {
      const { rows } = await client.query(
        `SELECT job, status, error, duration_ms FROM job_runs
         WHERE job = 'foxcash-psp-sweep' ORDER BY started_at DESC`,
      );
      runs = rows;
      if (runs.some((r) => r.status === 'SUCCESS')) break;
      await sleep(2000);
    }
    await client.end();

    expect(runs.length, 'o job foxcash-psp-sweep deve ter rodado (job_runs)').toBeGreaterThan(0);
    const ok = runs.find((r) => r.status === 'SUCCESS');
    expect(ok, 'a execução do sweep deve ser SUCCESS (excedente transferido)').toBeTruthy();
    expect(ok.error).toBeNull();
  });

  test('2. Replay no mesmo dia é idempotente — novas execuções seguem SUCCESS (sem duplicar)', async () => {
    if (process.env.FOXCASH_PSP_SWEEP_ENABLED !== 'true') {
      test.skip(true, 'requer FOXCASH_PSP_SWEEP_ENABLED=true (kill-switch do sweep)');
    }
    test.setTimeout(240_000);

    // O sweep usa UUID determinístico por dia ("sweep:"+LocalDate) → o code do
    // PSP é estável → replay não duplica (verificado em detalhe nos unitários).
    // Aqui: mais um ciclo do job continua SUCCESS — nenhum FAILED.
    const client = dbClient();
    await client.connect();
    const before = await client.query(
      `SELECT count(*)::int AS n FROM job_runs WHERE job = 'foxcash-psp-sweep'`,
    );
    const deadline = Date.now() + 180_000;
    while (Date.now() < deadline) {
      // Aguarda o count AUMENTAR (nova execução do job). Não usamos
      // `started_at > $1` como limite: o started_at tem precisão de
      // microssegundos e o JS Date trunca para ms — o `max` truncado casa com
      // a própria linha do max e o poll quebra na 1ª iteração (falso negativo).
      const { rows } = await client.query(
        `SELECT count(*)::int AS n FROM job_runs WHERE job = 'foxcash-psp-sweep'`,
      );
      if (rows[0].n > before.rows[0].n) break;
      await sleep(2000);
    }
    const after = await client.query(
      `SELECT count(*)::int AS n FROM job_runs WHERE job = 'foxcash-psp-sweep'`,
    );
    const failed = await client.query(
      `SELECT count(*)::int AS n FROM job_runs WHERE job = 'foxcash-psp-sweep' AND status = 'FAILED'`,
    );
    await client.end();

    expect(after.rows[0].n).toBeGreaterThan(before.rows[0].n);
    expect(failed.rows[0].n, 'nenhuma execução do sweep pode falhar').toBe(0);
  });
});
