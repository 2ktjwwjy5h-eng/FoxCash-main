// Imprime apenas o webhook secret (Spec 62 F7) — usado para assinar o payload
// do k6. Nunca loga em nenhum outro lugar.
process.stdout.write(process.env.FOXCASH_PSP_WEBHOOK_SECRET || '');
