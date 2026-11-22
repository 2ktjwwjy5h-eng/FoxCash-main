// Port verbatim do template (landing v2, Tailwind v4). Única adaptação:
// Link do react-router e rota de cadastro existente (/register).
import { ArrowRight } from 'lucide-react';
import { Link } from 'react-router-dom';

export function Hero() {
  return (
    <section className="relative overflow-hidden pt-32 pb-20 lg:pt-44 lg:pb-28">
      <div className="pointer-events-none absolute inset-0 flex items-center justify-center">
        <div
          className="absolute h-[600px] w-[600px] rounded-full opacity-20 blur-3xl"
          style={{
            background: 'radial-gradient(circle, oklch(0.68 0.2 42 / 0.35) 0%, transparent 70%)',
          }}
        />
      </div>

      <div className="relative mx-auto w-full max-w-[1400px] px-5 text-center lg:px-8">
        <span className="inline-flex items-center gap-2 rounded-full border border-primary/30 bg-primary/10 px-3 py-1 text-xs font-medium text-primary">
          Gateway + Bot em um só lugar
        </span>

        <h1 className="mx-auto mt-6 max-w-4xl text-4xl font-semibold leading-tight tracking-tight lg:text-6xl">
          Seu Telegram e Discord são suas <span className="text-primary">máquinas de venda</span>
        </h1>

        <p className="mx-auto mt-6 max-w-2xl text-base text-muted-foreground lg:text-lg">
          Automatize cobranças via PIX, gerencie leads, dispare campanhas e rastreie conversões. Apenas{' '}
          <span className="font-mono font-medium text-foreground">R$ 0,50</span> por transação e{' '}
          <span className="font-mono font-medium text-foreground">R$ 2,50</span> por saque.
        </p>

        <div className="mt-8 flex items-center justify-center">
          <Link
            to="/register"
            className="inline-flex items-center gap-2 rounded-full px-7 py-3 text-sm font-semibold text-primary-foreground transition-transform hover:-translate-y-px"
            style={{ backgroundImage: 'var(--gradient-fox)', boxShadow: 'var(--shadow-fox)' }}
          >
            Criar conta gratuita
            <ArrowRight className="size-4" />
          </Link>
        </div>

        <p className="mt-4 text-xs text-muted-foreground">Cadastro grátis · Sem cartão · Acesso imediato</p>
      </div>
    </section>
  );
}
