// Landing page pública do FoxCash (rota "/", Spec 129).
// Port fiel do template v2 (Tailwind v4): componentes em src/components/landing/
// com as classes do template verbatim. O tema escuro + laranja-fox é escopado
// em `.landing` (styles.css) — auth e dashboard continuam intactos.
// CTAs levam às rotas existentes do portal (/login, /register).

import { Header } from '../components/landing/Header';
import { Hero } from '../components/landing/Hero';
import { Metrics } from '../components/landing/Metrics';
import { Features } from '../components/landing/Features';
import { HowItWorks } from '../components/landing/HowItWorks';
import { Integrations } from '../components/landing/Integrations';
import { Pricing } from '../components/landing/Pricing';
import { Footer } from '../components/landing/Footer';

export default function LandingPage() {
  return (
    <div className="landing">
      <Header />
      <main>
        <Hero />
        <Metrics />
        <Features />
        <HowItWorks />
        <Integrations />
        <Pricing />
      </main>
      <Footer />
    </div>
  );
}
