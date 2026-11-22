import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import tailwindcss from '@tailwindcss/vite';

// Build da SPA do portal (Spec 46) — o estático é servido pelo próprio Spring
// Boot em src/main/resources/static (mesmo origin, sem CORS). Em dev, o proxy
// encaminha /api e /webhooks para o backend local (porta 8080).
export default defineConfig({
  // Tailwind v4 (Spec 129): usado apenas na landing — source restrito e
  // preflight desativado via styles.css, o restante do portal segue CSS puro.
  plugins: [react(), tailwindcss()],
  build: {
    outDir: '../src/main/resources/static',
    emptyOutDir: true,
  },
  server: {
    port: 5173,
    // Spec 143: o modal de termos importa docs/politicas-uso.md (`?raw`) — fora
    // do root do portal. O fs.allow libera o dev server para ler o arquivo.
    fs: {
      allow: ['..'],
    },
    proxy: {
      '/api': 'http://localhost:8080',
      '/webhooks': 'http://localhost:8080',
    },
  },
});
