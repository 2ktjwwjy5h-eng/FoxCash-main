// Port verbatim do template (landing v2, Tailwind v4) — sem adaptações.
import { Receipt, Banknote, ShieldCheck, Zap } from 'lucide-react';

const metrics = [
  {
    icon: Receipt,
    value: 'R$ 0,50',
    label: 'por transação aprovada',
  },
  {
    icon: Banknote,
    value: 'R$ 2,50',
    label: 'por saque realizado',
  },
  {
    icon: ShieldCheck,
    value: 'Gateway nativo',
    label: 'FoxCash 100% próprio',
  },
  {
    icon: Zap,
    value: 'PIX instantâneo',
    label: 'confirmação em segundos',
  },
];

export function Metrics() {
  return (
    <section className="py-10 lg:py-14">
      <div className="mx-auto grid w-full max-w-[1400px] gap-4 px-5 sm:grid-cols-2 lg:grid-cols-4 lg:px-8">
        {metrics.map((m) => (
          <article
            key={m.label}
            className="rounded-2xl border border-border bg-card/60 p-5 transition-colors hover:border-primary/30"
          >
            <span className="flex size-10 items-center justify-center rounded-xl border border-primary/30 bg-primary/10 text-primary">
              <m.icon className="size-5" />
            </span>
            <p className="mt-4 font-mono text-2xl font-semibold">{m.value}</p>
            <p className="mt-1 text-sm text-muted-foreground">{m.label}</p>
          </article>
        ))}
      </div>
    </section>
  );
}
