// @ts-check
const { test } = require('@playwright/test');
// Landing page pública (Spec 129) — rota "/" do portal.
//
// Requisitos: app rodando (default http://localhost:8080, servindo a SPA — o
// build do portal vai para src/main/resources/static), PostgreSQL acessível,
// perfil dev. A landing é pública — nenhum auth necessário.
//
// Cenários:
//   1. "/" renderiza a landing pública com as seções do template (hero, badge,
//      recursos, como funciona, integrações, preços).
//   2. Header deslogado (Login + Criar conta) e CTA do hero → /register.
//   3. Nav de âncoras + card de Preços ("Modelo transparente").
test.describe('Landing page (Spec 129)', () => {
  test.describe.configure({ mode: 'serial' });

  test('1. GET / serve a landing pública com as seções do template', async ({ page }) => {
    await page.goto('/');

    // Badge + hero.
    await page.getByText('Gateway + Bot em um só lugar').waitFor({ state: 'visible' });
    await page.getByRole('heading', { name: /máquinas de venda/ }).isVisible();

    // Títulos das seções.
    await page.getByText('Tudo que você precisa para vender no automático').isVisible();
    await page.getByText('Do cadastro à primeira venda em 5 minutos').isVisible();
    await page.getByText('Conectado ao seu ecossistema de vendas').isVisible();
    await page.getByText('Pague apenas pelo que usar').isVisible();

    // Métricas do hero (card de preço por transação — repete no Preços, usar first).
    await page.getByText('por transação aprovada').first().isVisible();
  });

  test('2. Header deslogado (Login + Criar conta) e CTA do hero → /register', async ({ page }) => {
    await page.goto('/');

    // Deslogado: links de entrada.
    await page.getByRole('link', { name: 'Login' }).isVisible();
    await page.getByRole('link', { name: 'Criar conta', exact: true }).first().isVisible();

    // CTA do hero leva ao cadastro (tela com a API key 1x — Spec 142).
    const cta = page.getByRole('link', { name: 'Criar conta gratuita' }).first();
    await cta.click();
    await page.waitForURL('**/register');
    await page.getByLabel('Nome').waitFor({ state: 'visible' });
  });

  test('3. Nav de âncoras + seção de Preços com o modelo transparente', async ({ page }) => {
    await page.goto('/');

    // Nav de âncoras (header + rodapé repetem os links — usar first).
    await page.getByRole('link', { name: 'Recursos' }).first().isVisible();
    await page.getByRole('link', { name: 'Integrações' }).first().isVisible();
    await page.getByRole('link', { name: 'Preços' }).first().isVisible();

    await page.getByText('Modelo transparente').isVisible();
    await page.getByText('por transação aprovada').first().isVisible();
    await page.getByText('por saque realizado').first().isVisible();
  });
});
