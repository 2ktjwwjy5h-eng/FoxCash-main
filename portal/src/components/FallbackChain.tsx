// FallbackChain (Spec 108) — fila horizontal da ordem de fallback dos
// adquirentes: Principal → Fallback 1 → Fallback 2, com o estado do circuito
// de cada um e a explicação da rotação automática em falha (Spec 91).

import type { GatewayProvider } from './GatewayCard';
import { Card } from './ui';

function circuitTag(state: string): string {
  switch (state) {
    case 'OPEN':
      return 'tag tag-danger';
    case 'HALF_OPEN':
      return 'tag tag-warning';
    default:
      return 'tag tag-success';
  }
}

export default function FallbackChain({ providers }: { providers: GatewayProvider[] }) {
  if (providers.length === 0) {
    return null;
  }
  return (
    <Card title="Ordem de fallback automática">
      <div className="fallback-chain" role="list">
        {providers.map((p, index) => (
          <div className="fallback-node" role="listitem" key={p.provider}>
            <div className="fallback-node-card">
              <div className="fallback-node-name">{p.displayName}</div>
              <div className="fallback-node-badge">
                <span className="tag tag-neutral">
                  {p.order === 1 ? 'Principal' : `Fallback ${p.order - 1}`}
                </span>
                <span className={circuitTag(p.circuitState)}>{p.circuitState}</span>
              </div>
            </div>
            {index < providers.length - 1 && <div className="fallback-arrow" aria-hidden="true">→</div>}
          </div>
        ))}
      </div>
      <p className="muted fallback-note">
        Rotação automática em falha (circuit breaker — Spec 91).
      </p>
    </Card>
  );
}
