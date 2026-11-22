// Declaração do widget Turnstile (Cloudflare) — Spec 63, Fase C.
// O script é carregado dinamicamente apenas na tela de login (render explícito).

interface TurnstileRenderOptions {
  sitekey: string;
  callback: (token: string) => void;
  /** Chamado quando o desafio falha (ex.: rede bloqueada) — sem token gerado. */
  'error-callback'?: () => void;
}

interface TurnstileWidget {
  render(container: HTMLElement | null, options: TurnstileRenderOptions): string;
  /** Regenera o token (reset do widget) — ex.: após o token ser consumido. */
  reset(widgetId: string): void;
}

interface Window {
  turnstile?: TurnstileWidget;
}
