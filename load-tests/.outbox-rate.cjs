const { Client } = require('../e2e-tests/node_modules/pg');
const t0 = Date.now();
(async () => {
  const c = new Client({ host:'127.0.0.1', port:15432, user:'postgres', password:'ZhGVRaUHXgnsWcZZPTFQqmoKBZQMYFoI', database:'railway' });
  await c.connect();
  const q = async (sql) => (await c.query(sql)).rows[0];
  const a = await q(`SELECT count(*) FILTER (WHERE status='PENDING') AS pending, count(*) FILTER (WHERE status='SENT') AS sent FROM notification_outbox`);
  const sleep = ms => new Promise(r=>setTimeout(r,ms));
  await sleep(60000);
  const b = await q(`SELECT count(*) FILTER (WHERE status='PENDING') AS pending, count(*) FILTER (WHERE status='SENT') AS sent FROM notification_outbox`);
  const dt = (Date.now()-t0)/1000;
  console.log(JSON.stringify({
    window_s: dt,
    pending_antes: a.pending, pending_depois: b.pending, pending_delta: Number(b.pending)-Number(a.pending),
    sent_antes: a.sent, sent_depois: b.sent, sent_delta: Number(b.sent)-Number(a.sent),
    drain_events_s: ((Number(b.sent)-Number(a.sent))/dt).toFixed(1)
  }, null, 1));
  await c.end();
})().catch(e=>{console.error(e.message);process.exit(1);});
