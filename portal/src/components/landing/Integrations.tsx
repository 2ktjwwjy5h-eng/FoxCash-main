// Port verbatim do template (landing v2, Tailwind v4) — sem adaptações.
import { CreditCard, Megaphone, ShieldCheck, Server } from 'lucide-react';

const groups = [
  {
    icon: CreditCard,
    title: 'Gateway & Pagamentos',
    items: ['Gateway Nativo FoxCash', '12+ gateways PIX parceiros', 'Confirmação automática'],
  },
  {
    icon: Megaphone,
    title: 'Tráfego e Ads',
    items: ['Facebook Ads', 'TikTok Ads', 'Google Ads', 'Kwai Ads'],
  },
  {
    icon: Server,
    title: 'Tracking Server-Side',
    description:
      'Sem bloqueio de Ad-Blocker. Integrações nativas com Meta CAPI, TikTok Events, UTMify, Xtracky e Trackfy.',
    items: ['Meta CAPI', 'TikTok Events', 'UTMify', 'Xtracky', 'Trackfy'],
  },
  {
    icon: ShieldCheck,
    title: 'Proteção e Uptime',
    items: ['Infraestrutura própria', 'Monitoramento 24/7', 'Ambiente seguro'],
  },
];

export function Integrations() {
  return (
    <section id="integracoes" className="py-20 lg:py-28">
      <div className="mx-auto w-full max-w-[1400px] px-5 lg:px-8">
        <div className="mb-12 text-center">
          <p className="text-xs font-semibold uppercase tracking-[0.18em] text-primary">Integrações</p>
          <h2 className="mt-3 text-3xl font-semibold tracking-tight lg:text-4xl">
            Conectado ao seu ecossistema de vendas
          </h2>
        </div>

        <div className="grid gap-5 md:grid-cols-2">
          {groups.map((g) => (
            <article
              key={g.title}
              className="rounded-2xl border border-border bg-card/60 p-6 transition-colors hover:border-primary/30"
            >
              <div className="mb-4 flex items-center gap-3">
                <span className="flex size-10 items-center justify-center rounded-xl border border-primary/30 bg-primary/10 text-primary">
                  <g.icon className="size-5" />
                </span>
                <h3 className="text-lg font-semibold">{g.title}</h3>
              </div>
              {g.description && <p className="mb-4 text-sm text-muted-foreground">{g.description}</p>}
              <ul className="flex flex-wrap gap-2">
                {g.items.map((item) => (
                  <li
                    key={item}
                    className="rounded-full border border-border bg-secondary/40 px-3 py-1 text-xs text-muted-foreground"
                  >
                    {item}
                  </li>
                ))}
              </ul>
            </article>
          ))}
        </div>
      </div>
    </section>
  );
}
