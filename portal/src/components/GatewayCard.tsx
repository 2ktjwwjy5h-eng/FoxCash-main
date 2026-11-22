// GatewayCard (Spec 108) — card de um adquirente conectado: nome amigável,
// ordem de fallback (Principal/Fallback N), estado do circuito (CLOSED/OPEN/
// HALF_OPEN) e extrato do período (volume + contagem).

import { formatMoney } from './ui';

export interface GatewayProvider {
  provider: string;
  displayName: string;
  order: number;
  circuitState: string;
  volumePeriod: number;
  countPeriod: number;
}

/** Badge do estado do circuito (Spec 91): CLOSED verde, OPEN vermelho, HALF_OPEN âmbar. */
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

export default function GatewayCard({ provider }: { provider: GatewayProvider }) {
  const orderLabel =
    provider.order === 1 ? 'Principal' : `Fallback ${provider.order - 1}`;
  return (
    <div className="card gateway-card">
      <div className="gateway-card-header">
        <span className="gateway-name">{provider.displayName}</span>
        <span className="tag tag-neutral">{orderLabel}</span>
      </div>
      <div className="gateway-card-body">
        <span className={circuitTag(provider.circuitState)}>{provider.circuitState}</span>
        <div className="gateway-extract">
          <div className="gateway-volume">{formatMoney(provider.volumePeriod)}</div>
          <div className="muted">
            {provider.countPeriod} {provider.countPeriod === 1 ? 'pagamento' : 'pagamentos'} no mês
          </div>
        </div>
      </div>
    </div>
  );
}
