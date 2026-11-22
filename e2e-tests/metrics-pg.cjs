const { Client } = require('pg');
(async () => {
  const c = new Client({ host:'127.0.0.1', port:15432, user:'postgres',
    password:'vpZhwdZEyTnGbHCzjjLnIsFaAQxhsgFj', database:'railway', connectionTimeoutMillis:8000 });
  await c.connect();
  for (let i=0;i<240;i++) {
    try {
      const r = await c.query(`
        SELECT count(*) FILTER (WHERE state='active') active,
               count(*) FILTER (WHERE state='idle') idle,
               (SELECT round(blks_hit*100.0/NULLIF(blks_hit+blks_read,0),2) FROM pg_stat_database WHERE datname='railway') cache_hit,
               (SELECT count(*) FROM pg_locks WHERE NOT granted) waiting_locks
        FROM pg_stat_activity WHERE datname='railway'`);
      console.log(`PG ${new Date().toISOString().slice(11,19)} active=${r.rows[0].active} idle=${r.rows[0].idle} cache_hit=${r.rows[0].cache_hit} waiting=${r.rows[0].waiting_locks}`);
    } catch(e){ console.log('PG err', e.message); }
    await new Promise(r=>setTimeout(r,1000));
  }
  await c.end();
})().catch(e=>{console.error(e.message);process.exit(1)});
