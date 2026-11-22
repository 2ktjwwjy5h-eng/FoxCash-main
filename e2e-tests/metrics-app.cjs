// Coleta métricas do app (CPU/GC/Hikari) do actuator prometheus (Spec 62 F7).
const { execSync } = require('child_process');
const ADMIN_KEY = process.env.ADMIN_KEY;
(async () => {
  const url = 'https://foxcash-production.up.railway.app/actuator/prometheus';
  for (let i = 0; i < 240; i++) {
    try {
      const out = execSync(`curl -s -H "X-Api-Key: ${ADMIN_KEY}" ${url}`, { maxBuffer: 5*1024*1024 }).toString();
      const get = (re) => { const m = out.match(re); return m ? m[1] : 'NA'; };
      console.log(`APP ${new Date().toISOString().slice(11,19)} cpu=${get(/process_cpu_usage\{[^}]*\}\s+([0-9.eE+-]+)/)} heap_old=${get(/jvm_memory_used_bytes\{[^}]*id="G1 Old Gen"\}\s+([0-9.eE+-]+)/)} hikari_active=${get(/hikaricp_connections_active\{[^}]*\}\s+([0-9.eE+-]+)/)} hikari_idle=${get(/hikaricp_connections_idle\{[^}]*\}\s+([0-9.eE+-]+)/)}`);
    } catch (e) { console.log(`APP ERR ${e.message}`); }
    await new Promise(r => setTimeout(r, 1000));
  }
})();
