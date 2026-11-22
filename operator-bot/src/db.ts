import pg from 'pg';

/** Pool de leitura (SELECT) — o bot nunca escreve no banco (Spec 67). */
export function createPool(databaseUrl: string): pg.Pool {
  return new pg.Pool({ connectionString: databaseUrl, max: 3 });
}

export interface DbQueries {
  connections(): Promise<{ active: number; max: number }>;
  outbox(): Promise<{ status: string; count: number }[]>;
  jobs(): Promise<{ job: string; status: string; started_at: Date }[]>;
  salesLastHour(): Promise<{ count: number; ticket: number }>;
  salesWindow(hours: number): Promise<{ count: number; amount: number }>;
  chargebacks(days: number): Promise<number>;
  merchants(): Promise<{ active: number; new7d: number }>;
  monthlyRevenue(): Promise<number>;
  topMerchants(limit: number): Promise<{ name: string; volume: number }[]>;
  securityAlerts(limit: number): Promise<{ type: string; created_at: Date }[]>;
  reconciliationOpen(): Promise<number>;
  lastReconciliation(): Promise<{ status: string; finished_at: Date | null } | null>;
}

export function queries(pool: pg.Pool): DbQueries {
  return {
    async connections() {
      const active = await pool.query<{ count: string }>(
        'SELECT count(*)::text FROM pg_stat_activity');
      // `SHOW max_connections` devolve a coluna com o nome do parâmetro
      // (max_connections), não `setting` — validado contra o Postgres real.
      const max = await pool.query<{ max_connections: string }>('SHOW max_connections');
      return { active: Number(active.rows[0].count), max: Number(max.rows[0].max_connections) };
    },
    async outbox() {
      const r = await pool.query<{ status: string; count: string }>(
        `SELECT status, count(*)::text AS count FROM notification_outbox
         WHERE created_at > now() - interval '1 hour' GROUP BY status`);
      return r.rows.map((x) => ({ status: x.status, count: Number(x.count) }));
    },
    async jobs() {
      const r = await pool.query<{ job: string; status: string; started_at: Date }>(
        'SELECT job, status, started_at FROM job_runs ORDER BY started_at DESC LIMIT 5');
      return r.rows;
    },
    async salesLastHour() {
      const r = await pool.query<{ count: string; ticket: string }>(
        `SELECT count(*)::text AS count, COALESCE(avg(amount),0)::text AS ticket
         FROM pix_transactions WHERE status='APPROVED'
         AND paid_at > now() - interval '1 hour'`);
      return { count: Number(r.rows[0].count), ticket: Number(r.rows[0].ticket) };
    },
    async salesWindow(hours) {
      const r = await pool.query<{ count: string; amount: string }>(
        `SELECT count(*)::text AS count, COALESCE(sum(amount),0)::text AS amount
         FROM pix_transactions WHERE status='APPROVED'
         AND paid_at > now() - ($1::int * interval '1 hour')`, [hours]);
      return { count: Number(r.rows[0].count), amount: Number(r.rows[0].amount) };
    },
    async chargebacks(days) {
      const r = await pool.query<{ count: string }>(
        'SELECT count(*)::text AS count FROM chargebacks WHERE created_at > now() - ($1::int * interval \'1 day\')',
        [days]);
      return Number(r.rows[0].count);
    },
    async merchants() {
      const r = await pool.query<{ active: string; new7d: string }>(
        `SELECT count(*) FILTER (WHERE status='ACTIVE')::text AS active,
                count(*) FILTER (WHERE created_at > now() - interval '7 days')::text AS new7d
         FROM merchants`);
      return { active: Number(r.rows[0].active), new7d: Number(r.rows[0].new7d) };
    },
    async monthlyRevenue() {
      const r = await pool.query<{ total: string }>(
        `SELECT COALESCE(sum(transaction_fees_total + monthly_fee),0)::text AS total
         FROM invoices WHERE billing_period = to_char(now(),'YYYY-MM')`);
      return Number(r.rows[0].total);
    },
    async topMerchants(limit) {
      const r = await pool.query<{ name: string; volume: string }>(
        `SELECT m.name, sum(t.amount)::text AS volume
         FROM pix_transactions t JOIN merchants m ON m.id = t.merchant_id
         WHERE t.status='APPROVED' AND t.paid_at >= date_trunc('month', now())
         GROUP BY m.id, m.name ORDER BY 2 DESC LIMIT $1`, [limit]);
      return r.rows.map((x) => ({ name: x.name, volume: Number(x.volume) }));
    },
    async securityAlerts(limit) {
      const r = await pool.query<{ type: string; created_at: Date }>(
        `SELECT type, created_at FROM notification_outbox
         WHERE type LIKE '%BURST%' OR type IN ('BODY_INSPECTION_BLOCKED','RATE_LIMIT_FAIL_OPEN')
         ORDER BY created_at DESC LIMIT $1`, [limit]);
      return r.rows;
    },
    async reconciliationOpen() {
      const r = await pool.query<{ count: string }>(
        "SELECT count(*)::text AS count FROM reconciliation_issues WHERE status='OPEN'");
      return Number(r.rows[0].count);
    },
    async lastReconciliation() {
      const r = await pool.query<{ status: string; finished_at: Date }>(
        `SELECT status, finished_at FROM job_runs
         WHERE job LIKE '%reconcil%' ORDER BY started_at DESC LIMIT 1`);
      return r.rows[0] ?? null;
    },
  };
}
