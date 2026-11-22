// GatewaysPage (Spec 108) — visão dos adquirentes do lojista: cards com
// extrato, ordem de fallback (Principal → Fallback N) e catálogo de
// integrações. Consome os endpoints read-only da Spec 107.

import { ErrorBanner, Spinner, useAsync } from '../components/ui';
import { api } from '../apiClient';
import GatewayCard, { type GatewayProvider } from '../components/GatewayCard';
import FallbackChain from '../components/FallbackChain';
import GatewayCatalog, { type GatewayCatalogItem } from '../components/GatewayCatalog';

interface GatewayOverview {
  providers: GatewayProvider[];
}

export default function GatewaysPage() {
  const overview = useAsync<GatewayOverview>(
    () => api.get('/api/v1/portal/gateways'),
    [],
  );
  const catalog = useAsync<GatewayCatalogItem[]>(
    () => api.get('/api/v1/portal/gateways/catalog'),
    [],
  );

  if (overview.loading || catalog.loading) {
    return <Spinner />;
  }
  if (overview.error) {
    return <ErrorBanner error={overview.error} />;
  }

  const providers = overview.data?.providers ?? [];

  return (
    <div className="page">
      <h1>Gateways</h1>

      {providers.length === 0 ? (
        <div className="empty-state">
          <p>Nenhum adquirente configurado.</p>
          <p className="muted">
            A ordem de roteamento é definida pela plataforma (Spec 91) — a
            visão aqui é informativa.
          </p>
        </div>
      ) : (
        <>
          <section aria-label="Ordem de fallback">
            <FallbackChain providers={providers} />
          </section>

          <section aria-label="Adquirentes conectados">
            <div className="gateway-grid">
              {providers.map((provider) => (
                <GatewayCard key={provider.provider} provider={provider} />
              ))}
            </div>
          </section>
        </>
      )}

      {catalog.error ? (
        <ErrorBanner error={catalog.error} />
      ) : (
        <section aria-label="Catálogo de gateways">
          <GatewayCatalog items={catalog.data ?? []} />
        </section>
      )}
    </div>
  );
}
