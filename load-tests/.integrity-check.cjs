const { Client } = require('../e2e-tests/node_modules/pg');
(async () => {
  const c = new Client({ host:'127.0.0.1', port:15432, user:'postgres', password:'ZhGVRaUHXgnsWcZZPTFQqmoKBZQMYFoI', database:'railway' });
  await c.connect();
  const q = async (sql) => (await c.query(sql)).rows[0];
  const dups = await q(`SELECT count(*) AS n FROM (SELECT merchant_id, idempotency_key FROM pix_transactions WHERE idempotency_key IS NOT NULL GROUP BY merchant_id, idempotency_key HAVING count(*) > 1) d`);
  const recon = await q(`SELECT count(*) AS n FROM reconciliation_issues WHERE status='OPEN'`);
  // Espelha a lógica oficial da Spec 30: lojista SEM entry de ledger (ex.: sem
  // movimentação) é consistente por definição — só diverge quem TEM ledger e o
  // saldo não bate com o balance_after da última entry.
  const walletDiv = await q(`SELECT count(*) AS n FROM (SELECT w.id FROM merchants w LEFT JOIN LATERAL (SELECT balance_after FROM wallet_ledger l WHERE l.merchant_id = w.id ORDER BY l.created_at DESC, l.id DESC LIMIT 1) x ON true WHERE x.balance_after IS NOT NULL AND w.wallet_balance IS DISTINCT FROM x.balance_after) d`);
  const deadlocks = await q(`SELECT count(*) AS n FROM job_runs WHERE error LIKE '%deadlock%' OR error LIKE '%Deadlock%'`);
  const outbox = await q(`SELECT count(*) FILTER (WHERE status='PENDING') AS pending, count(*) FILTER (WHERE status='FAILED') AS failed, count(*) FILTER (WHERE status='SENT') AS sent FROM notification_outbox`);
  const tx = await q(`SELECT count(*) AS total, count(*) FILTER (WHERE status='PENDING') AS pending FROM pix_transactions`);
  console.log(JSON.stringify({dups:Number(dups.n), reconciliation_open:Number(recon.n), wallet_vs_ledger_divergence:Number(walletDiv.n), deadlocks:Number(deadlocks.n), outbox:{pending:Number(outbox.pending), failed:Number(outbox.failed), sent:Number(outbox.sent)}, transactions:{total:Number(tx.total), pending:Number(tx.pending)}}, null, 1));
  await c.end();
})().catch(e=>{console.error(e.message);process.exit(1);});
