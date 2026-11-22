// baseline-pre-teste.cjs — coleta READ-ONLY do estado do Postgres Railway
// para o baseline de integridade pre-F8. Nenhuma escrita, nenhum comando
// destrutivo, nenhuma geracao de carga. Requer tunel `railway connect -s Postgres -p 15432`.
const { Client } = require('../e2e-tests/node_modules/pg');

const C = { host: '127.0.0.1', port: 15432, user: 'postgres',
  password: 'vpZhwdZEyTnGbHCzjjLnIsFaAQxhsgFj', database: 'railway',
  connectionTimeoutMillis: 10000 };

const q = async (c, sql, params) => (await c.query(sql, params)).rows;

(async () => {
  const c = new Client(C);
  await c.connect();
  const out = [];
  const line = (k, v) => out.push({ k, v });
  const now = await q(c, 'select now() as now');
  line('coleta_utc', now[0].now.toISOString());

  // ---------- 1. Saude Postgres / conexoes ----------
  const db = await q(c, `
    SELECT numbackends, xact_commit, xact_rollback, deadlocks,
           blks_hit, blks_read,
           round(blks_hit*100.0/NULLIF(blks_hit+blks_read,0),2) AS cache_hit_pct,
           pg_size_pretty(pg_database_size('railway')) AS db_size
    FROM pg_stat_database WHERE datname='railway'`);
  const act = await q(c, `
    SELECT count(*) AS total,
           count(*) FILTER (WHERE state='active') AS active,
           count(*) FILTER (WHERE state='idle') AS idle,
           count(*) FILTER (WHERE state='idle in transaction') AS idle_in_txn,
           count(*) FILTER (WHERE wait_event_type='Lock') AS waiting_lock,
           count(*) FILTER (WHERE wait_event_type='IO') AS waiting_io
    FROM pg_stat_activity WHERE datname='railway'`);
  line('conexoes', act[0]);
  line('pg_stat_database', db[0]);

  // ---------- 2. Locks ----------
  const locks = await q(c, `
    SELECT granted, mode, count(*) FROM pg_locks
    GROUP BY granted, mode ORDER BY granted, count(*) DESC`);
  line('locks_por_modo', locks);
  const locks_waits = await q(c, `
    SELECT count(*) AS waiting FROM pg_locks WHERE NOT granted`);
  line('locks_em_espera', locks_waits[0].waiting);

  // ---------- 3. Transacoes / vendas ----------
  const txn_status = await q(c, `
    SELECT status, count(*) AS n, sum(amount) AS amount_total
    FROM pix_transactions GROUP BY status ORDER BY n DESC`);
  line('pix_transactions_por_status', txn_status);
  const txn_total = await q(c, `
    SELECT count(*) AS total, count(DISTINCT idempotency_key) AS distinct_keys,
           count(*) FILTER (WHERE idempotency_key IS NULL) AS null_keys,
           min(created_at) AS oldest, max(created_at) AS newest
    FROM pix_transactions`);
  line('pix_transactions_total', txn_total[0]);

  // ---------- 4. Duplicacoes idempotency_key ----------
  const dup = await q(c, `
    SELECT count(*) AS duplicated_keys, sum(n) AS extra_rows FROM (
      SELECT idempotency_key, count(*) AS n FROM pix_transactions
      GROUP BY idempotency_key HAVING count(*) > 1) d`);
  line('duplicacoes_idempotency', dup[0]);

  // ---------- 5. Integridade wallet == ultimo balance_after do ledger ----------
  const wallet = await q(c, `
    WITH last_entry AS (
      SELECT DISTINCT ON (merchant_id) merchant_id, balance_after
      FROM wallet_ledger ORDER BY merchant_id, created_at DESC, id DESC
    )
    SELECT count(*) AS merchants_total,
           count(w.wallet_balance) AS merchants_with_balance,
           count(l.balance_after) AS merchants_with_ledger,
           count(*) FILTER (WHERE w.wallet_balance IS DISTINCT FROM l.balance_after) AS mismatches
    FROM merchants w LEFT JOIN last_entry l ON l.merchant_id = w.id`);
  line('wallet_vs_ledger', wallet[0]);
  const wallet_mismatch_detail = await q(c, `
    WITH last_entry AS (
      SELECT DISTINCT ON (merchant_id) merchant_id, balance_after
      FROM wallet_ledger ORDER BY merchant_id, created_at DESC, id DESC
    )
    SELECT w.id, w.name, w.wallet_balance, l.balance_after
    FROM merchants w JOIN last_entry l ON l.merchant_id = w.id
    WHERE w.wallet_balance IS DISTINCT FROM l.balance_after LIMIT 20`);
  line('wallet_mismatch_detalhe', wallet_mismatch_detail);

  // ---------- 6. Issues de reconciliacao em aberto ----------
  const issues = await q(c, `
    SELECT status, count(*) FROM reconciliation_issues GROUP BY status ORDER BY count(*) DESC`);
  line('reconciliation_issues', issues);

  // ---------- 7. Outbox ----------
  const outbox_status = await q(c, `
    SELECT status, count(*) AS n,
           min(created_at) AS oldest, max(created_at) AS newest,
           round(avg(extract(epoch FROM (now()-created_at))/3600.0)::numeric,1) AS avg_age_h
    FROM notification_outbox GROUP BY status ORDER BY n DESC`);
  line('outbox_por_status', outbox_status);
  const outbox_attempts = await q(c, `
    SELECT status, attempts, count(*) AS n
    FROM notification_outbox GROUP BY status, attempts ORDER BY status, attempts LIMIT 30`);
  line('outbox_attempts', outbox_attempts);
  const outbox_rate = await q(c, `
    SELECT
      count(*) FILTER (WHERE status='SENT' AND sent_at > now()-interval '1 hour') AS sent_1h,
      count(*) FILTER (WHERE status='SENT' AND sent_at > now()-interval '24 hours') AS sent_24h,
      count(*) FILTER (WHERE status='PENDING' AND created_at > now()-interval '1 hour') AS pending_created_1h,
      max(sent_at) AS last_sent_at,
      max(created_at) AS last_created_at
    FROM notification_outbox`);
  line('outbox_rate_recente', outbox_rate[0]);

  // ---------- 8. WAL ----------
  const wal = await q(c, `
    SELECT name, setting, unit FROM pg_settings
    WHERE name IN ('wal_level','max_wal_size','min_wal_size','wal_keep_size',
                   'checkpoint_timeout','max_connections','shared_buffers',
                   'effective_cache_size','work_mem','max_worker_processes')`);
  line('pg_settings_wal', wal);
  try {
    const wal_files = await q(c, `
      SELECT count(*) AS n_files, round(sum(size)/1024.0/1024.0,1) AS size_mb
      FROM pg_ls_waldir()`);
    line('wal_dir', wal_files[0]);
  } catch (e) { line('wal_dir_erro', e.message); }
  const lsn = await q(c, `
    SELECT pg_current_wal_lsn() AS lsn,
           round(pg_wal_lsn_diff(pg_current_wal_lsn(), '0/0')/1024.0/1024.0,1) AS lsn_mb,
           round(pg_wal_lsn_diff(pg_current_wal_lsn(), pg_current_wal_insert_lsn())/1024.0/1024.0,1) AS lag_insert_mb`);
  line('wal_lsn', lsn[0]);

  // ---------- 9. Disco por tabela ----------
  const sizes = await q(c, `
    SELECT tablename, pg_size_pretty(pg_total_relation_size('public.'||tablename)) AS size
    FROM pg_tables WHERE schemaname='public'
    ORDER BY pg_total_relation_size('public.'||tablename) DESC LIMIT 15`);
  line('tabelas_maiores', sizes);

  // ---------- 10. pg_stat_statements (top 8 por tempo total) ----------
  try {
    const pss = await q(c, `
      SELECT round(total_exec_time::numeric,0) AS total_ms, calls,
             round(mean_exec_time::numeric,2) AS mean_ms,
             left(query, 90) AS query
      FROM pg_stat_statements ORDER BY total_exec_time DESC LIMIT 8`);
    line('pg_stat_statements_top', pss);
  } catch (e) { line('pg_stat_statements_erro', e.message); }

  await c.end();
  console.log(JSON.stringify(out, null, 1));
})().catch(e => { console.error('ERRO:', e.message); process.exit(1); });
