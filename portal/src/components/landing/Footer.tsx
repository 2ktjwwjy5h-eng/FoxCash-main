// Port verbatim do template (landing v2, Tailwind v4). Únicas adaptações:
// Link do react-router, rota de cadastro existente (/register) e logo local.
import { Link } from 'react-router-dom';
import mark from '../../assets/foxcash-mark.png';

const links = [
  { to: '/#recursos', label: 'Recursos' },
  { to: '/#integracoes', label: 'Integrações' },
  { to: '/#precos', label: 'Preços' },
  { to: '/register', label: 'Criar conta' },
];

export function Footer() {
  return (
    <footer className="border-t border-border/60 bg-background py-12">
      <div className="mx-auto flex w-full max-w-[1400px] flex-col items-center justify-between gap-6 px-5 lg:flex-row lg:px-8">
        <Link to="/" className="flex items-center gap-2.5">
          <img src={mark} alt="Fox Cash" className="size-8 rounded-full" />
          <span className="text-sm font-semibold tracking-[0.14em]">
            FOX<span className="text-primary">CASH</span>
          </span>
        </Link>

        <nav className="flex flex-wrap items-center justify-center gap-6">
          {links.map((l) => (
            <Link key={l.to} to={l.to} className="text-sm text-muted-foreground transition-colors hover:text-foreground">
              {l.label}
            </Link>
          ))}
        </nav>

        <p className="text-xs text-muted-foreground">© {new Date().getFullYear()} Fox Cash. Todos os direitos reservados.</p>
      </div>
    </footer>
  );
}
