// @ts-check
const { test } = require('@playwright/test');
// Tela de cadastro de lojista (Spec 39/143) — UI real no portal.
//
// Requisitos: app rodando (default http://localhost:8080, servindo a SPA — o
// build do portal vai para src/main/resources/static), PostgreSQL acessível,
// perfil dev (Turnstile OFF — o cadastro não exige token no dev).
//
// Cenários:
//   1. Formulário exige nome/e-mail/senha + aceite dos termos → cria a conta e
//      mostra a API key UMA vez (tela de sucesso).
//   2. Sem aceitar os termos, o botão "Criar conta" fica desabilitado.
//   3. Link "Criar conta" existe na tela de login.
//   4. Modal de termos abre na PRÓPRIA tela (Spec 143) — sem navegar para o
//      GitHub; abrir o modal não marca o checkbox.
test.describe('Cadastro de lojista (Spec 39) — tela de registro', () => {
  test.describe.configure({ mode: 'serial' });

  test('1. Cadastro com nome/e-mail/senha + termos → API key exibida uma vez', async ({ page }) => {
    const email = `cadastro.e2e.${Date.now()}@example.com`;

    await page.goto('/register');

    // Campos obrigatórios presentes.
    await page.getByLabel('Nome').fill('Lojista Cadastro E2E');
    await page.getByLabel('E-mail').fill(email);
    await page.getByLabel('Senha').fill('Senha-E2E-123');

    // Sem aceitar os termos, o botão fica desabilitado (exigência da tela).
    const submit = page.getByRole('button', { name: 'Criar conta' });
    await submit.isDisabled().then((disabled) => {
      if (!disabled) throw new Error('botão "Criar conta" deveria estar desabilitado sem aceitar os termos');
    });

    // Aceita os termos → habilita e envia.
    await page.getByRole('checkbox').check();
    await submit.click();

    // Sucesso: tela da API key (exibida uma única vez) + link para o login.
    await page.getByText('Conta criada — guarde sua chave de API').waitFor({ timeout: 15_000 });
    await page.getByRole('button', { name: 'Fazer login' }).isVisible();

    // A chave aparece no box .key-value (formato de API key do projeto).
    const keyBox = await page.locator('.key-value').first().textContent();
    if (!keyBox || keyBox.trim().length < 20) {
      throw new Error('API key não exibida na tela de sucesso');
    }
  });

  test('2. Tela de login tem o link "Criar conta"', async ({ page }) => {
    await page.goto('/login');
    await page.getByRole('link', { name: 'Criar conta' }).isVisible();
  });

  test('3. Modal de termos abre na própria tela (sem navegar para o GitHub)', async ({ page }) => {
    await page.goto('/register');
    const checkbox = page.getByRole('checkbox');

    // Abre o modal clicando em "termos de uso e condições" (botão, não link).
    await page.getByRole('button', { name: 'termos de uso e condições' }).click();

    // Conteúdo real do docs/politicas-uso.md renderizado no modal (termos v2,
    // 2026-08-17 — texto jurídico B2B com cláusulas numeradas).
    await page.getByRole('heading', { name: 'TERMOS DE USO E CONDIÇÕES DE SERVIÇO' }).waitFor({ state: 'visible' });
    await page.getByRole('heading', { name: 'CLÁUSULA NONA' }).isVisible();
    // Trecho único do texto novo — prova que é o documento atualizado no modal.
    await page.getByText('dpo@foxcash.example.com').isVisible();

    // Abrir o modal NÃO deve marcar o checkbox (button dentro do label com
    // preventDefault/stopPropagation — a leitura dos termos não é aceite).
    if (await checkbox.isChecked()) {
      throw new Error('checkbox não deveria marcar ao abrir o modal de termos');
    }

    // Fecha pelo "Entendi" → o conteúdo do modal some.
    await page.getByRole('button', { name: 'Entendi' }).click();
    await page.getByRole('heading', { name: 'TERMOS DE USO E CONDIÇÕES DE SERVIÇO' }).waitFor({ state: 'detached' });
  });
});
