// Port verbatim do template (landing v2, Tailwind v4) — sem adaptações.
import { Wallet, Workflow, Bot, CreditCard } from 'lucide-react';

const features = [
  {
    icon: Wallet,
    title: 'Gateway Nativo FoxCash',
    description:
      'Sua conta com infraestrutura de pagamentos própria, subcontas e carteira digital integradas diretamente ao bot. Sem intermediários obrigatórios.',
    highlight: 'R$ 0,50 por transação · R$ 2,50 por saque',
  },
  {
    icon: Bot,
    title: 'Bot para Telegram e Discord',
    description:
      'Atenda leads, receba pedidos e confirme pagamentos automaticamente nos dois principais canais de comunidade do mercado.',
    highlight: '24/7 automatizado',
  },
  {
    icon: Workflow,
    title: 'Fluxo Básico',
    description:
      'Ideal para vendas rápidas e configuração em minutos: /start → Mensagem → Pagamento. Pronto para começar a vender.',
    highlight: 'Configure em minutos',
  },
  {
    icon: CreditCard,
    title: 'Fluxo Avançado',
    description:
      'Editor visual tipo N8N com condições, delays, loops e ramificações. Crie jornadas complexas sem escrever código.',
    highlight: 'Zero código necessário',
  },
];

export function Features() {
  return (
    <section id="recursos" className="py-20 lg:py-28">
      <div className="mx-auto w-full max-w-[1400px] px-5 lg:px-8">
        <div className="mb-12 max-w-2xl">
          <p className="text-xs font-semibold uppercase tracking-[0.18em] text-primary">Recursos</p>
          <h2 className="mt-3 text-3xl font-semibold tracking-tight lg:text-4xl">
            Tudo que você precisa para vender no automático
          </h2>
        </div>

        <div className="grid gap-5 md:grid-cols-2">
          {features.map((f) => (
            <article
              key={f.title}
              className="rounded-2xl border border-border bg-card/60 p-6 transition-colors hover:border-primary/30"
            >
              <div className="mb-4 flex items-center gap-3">
                <span className="flex size-10 items-center justify-center rounded-xl border border-primary/30 bg-primary/10 text-primary">
                  <f.icon className="size-5" />
                </span>
                <h3 className="text-lg font-semibold">{f.title}</h3>
              </div>
              <p className="text-sm leading-relaxed text-muted-foreground">{f.description}</p>
              <p className="mt-4 inline-flex items-center rounded-full bg-primary/10 px-3 py-1 text-xs font-medium text-primary">
                {f.highlight}
              </p>
            </article>
          ))}
        </div>
      </div>
    </section>
  );
}
