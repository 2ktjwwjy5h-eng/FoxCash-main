// Diagnóstico de conexões do Postgres Railway (Spec 62 F7) — pós-estágio 500.
const { Client } = require('pg');

(async () => {
  const c = new Client({
    host: '127.0.0.1', port: 15432,
    user: process.env.DB_USERNAME, password: process.env.DB_PASSWORD,
    database: 'railway',
    connectionTimeoutMillis: 8000,
  });
  try {
    await c.connect();
    const mc = await c.query('SHOW max_connections');
    console.log('RESULT: max_connections =', mc.rows[0].max_connections);
    const conns = await c.query(
      "SELECT count(*) AS total, count(*) FILTER (WHERE state='active') AS active, count(*) FILTER (WHERE state='idle') AS idle FROM pg_stat_activity");
    console.log('RESULT: conexoes:', JSON.stringify(conns.rows[0]));
    const byDb = await c.query(
      "SELECT datname, count(*) FROM pg_stat_activity GROUP BY datname ORDER BY 2 DESC LIMIT 5");
    console.log('RESULT: por db:', JSON.stringify(byDb.rows));
  } catch (e) {
    console.log('RESULT: ERRO ao conectar:', e.message);
  }
  await c.end().catch(() => {});
})().catch((e) => { console.error('ERRO:', e.message); process.exit(1); });
