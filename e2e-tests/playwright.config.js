// @ts-check
const { defineConfig } = require('@playwright/test');

/**
 * Config da suite E2E do FoxCash.
 * Requer a aplicacao rodando (default http://localhost:8080) e o PostgreSQL
 * acessivel (mesmas credenciais do application.yml).
 */
module.exports = defineConfig({
  testDir: './tests',
  globalSetup: './global-setup.js',
  timeout: 30_000,
  fullyParallel: false,
  workers: 1, // os cenarios sao seriais e compartilham estado (merchantId etc.)
  reporter: [['list']],
  use: {
    baseURL: process.env.API_BASE_URL || 'http://localhost:8080',
    // SEM Content-Type global: o fixture `request` do Playwright já define o
    // header correto a partir do `data` (JSON) / `form` / `multipart`; um
    // Content-Type fixo aqui vazaria para o `fetch` do BROWSER (Spec 80 — o
    // MediaUploader usa FormData e precisa do `multipart/form-data; boundary=`
    // que o navegador só monta quando o header NÃO vem pré-definido).
  },
});
