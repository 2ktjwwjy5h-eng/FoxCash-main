import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// Build da SPA do Master Console (Spec 71) — o estático é servido pelo próprio
// Spring Boot em src/main/resources/static/console (mesmo origin, sem CORS).
// base '/console/' — os assets do build referenciam /console/assets/... (o
// ConsoleSpaController só encaminha as ROTAS client-side; os assets são servidos
// pelo handler de recursos estáticos). Em dev, o proxy encaminha /api e
// /webhooks para o backend local (porta 8080).
export default defineConfig({
  plugins: [react()],
  base: '/console/',
  build: {
    outDir: '../src/main/resources/static/console',
    emptyOutDir: true,
  },
  server: {
    port: 5174,
    proxy: {
      '/api': 'http://localhost:8080',
      '/webhooks': 'http://localhost:8080',
    },
  },
});
