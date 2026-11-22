// Port verbatim do template (landing v2, Tailwind v4) — sem adaptações.
import { UserPlus, Bot, QrCode, TrendingUp } from 'lucide-react';

const steps = [
  {
    icon: UserPlus,
    title: 'Crie sua conta',
    description:
      'Cadastro gratuito, sem cartão, com acesso instantâneo ao dashboard e ativação imediata do seu merchant no gateway.',
  },
  {
    icon: Bot,
    title: 'Configure seu bot',
    description:
      'Insira o token do BotFather (Telegram) ou Webhook (Discord), defina o fluxo e personalize as mensagens.',
  },
  {
    icon: QrCode,
    title: 'Ative seu Gateway PIX',
    description:
      'Utilize o Gateway Nativo FoxCash ou escolha entre mais de 12 gateways parceiros conectando suas credenciais de API.',
  },
  {
    icon: TrendingUp,
    title: 'Comece a vender',
    description:
      'Bot operando 24/7 com acompanhamento de métricas, leads, faturamento e vendas em tempo real.',
  },
];

export function HowItWorks() {
  return (
    <section className="border-y border-border/60 bg-secondary/20 py-20 lg:py-28">
      <div className="mx-auto w-full max-w-[1400px] px-5 lg:px-8">
        <div className="mb-12 text-center">
          <p className="text-xs font-semibold uppercase tracking-[0.18em] text-primary">Como funciona</p>
          <h2 className="mt-3 text-3xl font-semibold tracking-tight lg:text-4xl">
            Do cadastro à primeira venda em 5 minutos
          </h2>
          <p className="mx-auto mt-4 max-w-xl text-sm text-muted-foreground">
            ~5 min para configurar · 0 código necessário · 24/7 vendendo automático
          </p>
        </div>

        <div className="grid gap-6 md:grid-cols-2 lg:grid-cols-4">
          {steps.map((s, i) => (
            <article key={s.title} className="relative rounded-2xl border border-border bg-card/60 p-6">
              <span className="mb-4 flex size-10 items-center justify-center rounded-xl border border-primary/30 bg-primary/10 text-primary">
                <s.icon className="size-5" />
              </span>
              <span className="absolute right-4 top-4 font-mono text-2xl font-bold text-muted-foreground/30">
                0{i + 1}
              </span>
              <h3 className="text-base font-semibold">{s.title}</h3>
              <p className="mt-2 text-sm leading-relaxed text-muted-foreground">{s.description}</p>
            </article>
          ))}
        </div>
      </div>
    </section>
  );
}
