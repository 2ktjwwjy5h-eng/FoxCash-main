// Port verbatim do template (landing v2, Tailwind v4). Únicas adaptações:
// Link do react-router (o portal não usa @tanstack/react-router), rota de
// cadastro existente (/register) e logo local (importado de src/assets — o
// Vite emite em /assets/** que é público no Spring Security).
// Header com vidro translúcido + blur e border-b, idêntico ao template e ao
// que está no ar (foxcash.example.com).
import { Link } from 'react-router-dom';
import { Menu, X } from 'lucide-react';
import { useState } from 'react';
import { cn } from '../../lib/utils';
import mark from '../../assets/foxcash-mark.png';

const links = [
  { to: '/#recursos', label: 'Recursos' },
  { to: '/#integracoes', label: 'Integrações' },
  { to: '/#precos', label: 'Preços' },
];

export function Header() {
  const [open, setOpen] = useState(false);

  return (
    <header className="fixed left-0 right-0 top-0 z-50 border-b border-border/60 bg-background/80 backdrop-blur-md">
      <div className="mx-auto flex h-16 w-full max-w-[1400px] items-center justify-between px-5 lg:px-8">
        <Link to="/" className="flex items-center gap-2.5">
          <img src={mark} alt="Fox Cash" className="size-8 rounded-full" />
          <span className="text-sm font-semibold tracking-[0.14em]">
            FOX<span className="text-primary">CASH</span>
          </span>
        </Link>

        <nav className="hidden items-center gap-8 md:flex">
          {links.map((l) => (
            <a
              key={l.to}
              href={l.to}
              className="text-sm text-muted-foreground transition-colors hover:text-foreground"
            >
              {l.label}
            </a>
          ))}
        </nav>

        <div className="hidden items-center gap-3 md:flex">
          <Link
            to="/login"
            className="text-sm font-medium text-muted-foreground transition-colors hover:text-foreground"
          >
            Login
          </Link>
          <Link
            to="/register"
            className="inline-flex items-center justify-center rounded-full px-5 py-2 text-sm font-medium text-primary-foreground transition-transform hover:-translate-y-px"
            style={{ backgroundImage: 'var(--gradient-fox)', boxShadow: 'var(--shadow-fox)' }}
          >
            Criar conta
          </Link>
        </div>

        <button
          aria-label={open ? 'Fechar menu' : 'Abrir menu'}
          onClick={() => setOpen((v) => !v)}
          className="rounded-md p-2 text-muted-foreground md:hidden"
        >
          {open ? <X className="size-5" /> : <Menu className="size-5" />}
        </button>
      </div>

      <div
        className={cn(
          'overflow-hidden border-b border-border/60 bg-background transition-all duration-300 md:hidden',
          open ? 'max-h-96 opacity-100' : 'max-h-0 opacity-0',
        )}
      >
        <nav className="flex flex-col gap-1 px-5 py-4">
          {links.map((l) => (
            <a
              key={l.to}
              href={l.to}
              onClick={() => setOpen(false)}
              className="rounded-md px-3 py-2 text-sm text-muted-foreground hover:bg-secondary"
            >
              {l.label}
            </a>
          ))}
          <hr className="my-2 border-border" />
          <Link
            to="/login"
            onClick={() => setOpen(false)}
            className="rounded-md px-3 py-2 text-sm text-muted-foreground hover:bg-secondary"
          >
            Login
          </Link>
          <Link
            to="/register"
            onClick={() => setOpen(false)}
            className="mt-1 inline-flex items-center justify-center rounded-full px-5 py-2 text-sm font-medium text-primary-foreground"
            style={{ backgroundImage: 'var(--gradient-fox)' }}
          >
            Criar conta
          </Link>
        </nav>
      </div>
    </header>
  );
}
