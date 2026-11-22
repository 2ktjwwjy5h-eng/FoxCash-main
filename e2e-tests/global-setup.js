// @ts-check
const { Client } = require('pg');

/**
 * Setup global: limpa o banco antes de cada execucao da suite E2E.
 * Ordem respeita as foreign keys (filhas antes dos pais): chargebacks,
 * withdrawals e merchant_risk referenciam merchants; master_sessions
 * referencia masters.
 */
module.exports = async () => {
  const client = new Client({
    host: process.env.DB_HOST || 'localhost',
    port: Number(process.env.DB_PORT || 5432),
    user: process.env.DB_USERNAME || 'nyxcash',
    password: process.env.DB_PASSWORD || 'nyxcash',
    database: process.env.DB_NAME || 'nyxcash',
  });

  await client.connect();
  await client.query(
    'TRUNCATE TABLE chargebacks, withdrawals, merchant_risk, store_bots, bot_leads, bot_funnel_events, notification_outbox, ' +
      'receivables, invoices, pix_transactions, merchants, merchant_credentials, merchant_sessions, ' +
      'gift_presets, merchant_api_keys, master_sessions, masters, wallet_ledger, ' +
      'master_ledger, master_audit_log, reconciliation_issues, password_reset_tokens, ' +
      'platform_activity_log, stores, plans, plan_delivery_actions, plan_deliveries, ' +
      'plan_delivery_actions_exec, ' +
      'master_mfa_methods, master_totp, master_recovery_codes, ' +
      'merchant_mfa_methods, merchant_totp, merchant_recovery_codes, ' + // Spec 94: MFA do lojista
      'tracking_profiles ' + // Spec 81: cascade trunca tracking_profile_bots e event_logs
      'RESTART IDENTITY CASCADE',
  );
  await client.end();
  console.log('[global-setup] Banco de testes limpo (todas as tabelas de dominio)');
};
