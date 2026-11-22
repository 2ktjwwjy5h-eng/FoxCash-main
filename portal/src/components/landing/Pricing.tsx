// Port verbatim do template (landing v2, Tailwind v4). Única adaptação:
// Link do react-router e rota de cadastro existente (/register).
import { Check } from 'lucide-react';
import { Link } from 'react-router-dom';

const features = [
  'Gateway nativo FoxCash',
  'Bot Telegram + Discord',
  '2 editores de fluxo',
  'PIX instantâneo',
  'Tracking server-side',
  'Webhooks e API',
];

export function Pricing() {
  return (
    <section id="precos" className="border-y border-border/60 bg-secondary/20 py-20 lg:py-28">
      <div className="mx-auto w-full max-w-[1400px] px-5 lg:px-8">
        <div className="mb-12 text-center">
          <p className="text-xs font-semibold uppercase tracking-[0.18em] text-primary">Preços</p>
          <h2 className="mt-3 text-3xl font-semibold tracking-tight lg:text-4xl">Pague apenas pelo que usar</h2>
          <p className="mx-auto mt-4 max-w-xl text-sm text-muted-foreground">
            Sem mensalidade obrigatória. Custo fixo por transação e por saque.
          </p>
        </div>

        <div className="mx-auto max-w-md rounded-3xl border border-primary/30 bg-card/60 p-8 text-center shadow-xl">
          <p className="text-sm font-medium text-muted-foreground">Modelo transparente</p>
          <div className="mt-6 flex items-center justify-center gap-6">
            <div>
              <p className="font-mono text-4xl font-bold text-primary">R$ 0,50</p>
              <p className="mt-1 text-xs text-muted-foreground">por transação aprovada</p>
            </div>
            <div className="h-12 w-px bg-border" />
            <div>
              <p className="font-mono text-4xl font-bold text-primary">R$ 2,50</p>
              <p className="mt-1 text-xs text-muted-foreground">por saque realizado</p>
            </div>
          </div>

          <ul className="mt-8 space-y-3 text-left">
            {features.map((f) => (
              <li key={f} className="flex items-center gap-3 text-sm text-muted-foreground">
                <span className="flex size-5 shrink-0 items-center justify-center rounded-full bg-primary/10 text-primary">
                  <Check className="size-3" />
                </span>
                {f}
              </li>
            ))}
          </ul>

          <Link
            to="/register"
            className="mt-8 inline-flex w-full items-center justify-center rounded-full px-6 py-3 text-sm font-semibold text-primary-foreground transition-transform hover:-translate-y-px"
            style={{ backgroundImage: 'var(--gradient-fox)', boxShadow: 'var(--shadow-fox)' }}
          >
            Criar conta gratuita
          </Link>
        </div>
      </div>
    </section>
  );
}
