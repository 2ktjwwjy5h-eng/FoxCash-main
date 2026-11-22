// GatewayCatalog (Spec 108) — grade de integrações disponíveis divididas por
// Pix/Cartão/Cripto, com badges available/configured. Read-only: a ativação é
// evolução (mudaria o router da Spec 91).

import { Card } from './ui';

export interface GatewayCatalogItem {
  id: string;
  name: string;
  category: string;
  available: boolean;
  configured: boolean;
}

const CATEGORIES = ['PIX', 'CARTÃO', 'CRIPTO'] as const;

export default function GatewayCatalog({ items }: { items: GatewayCatalogItem[] }) {
  if (items.length === 0) {
    return null;
  }
  return (
    <Card title="Catálogo de gateways disponíveis">
      <div className="gateway-catalog">
        {CATEGORIES.map((category) => {
          const inCategory = items.filter((i) => i.category === category);
          return (
            <div className="gateway-catalog-section" key={category}>
              <h3 className="gateway-catalog-title">{category}</h3>
              {inCategory.length === 0 ? (
                <p className="muted">Nenhuma integração nesta categoria.</p>
              ) : (
                inCategory.map((item) => (
                  <div className="gateway-catalog-item" key={`${category}-${item.id}`}>
                    <span className="gateway-catalog-name">{item.name}</span>
                    <span className="gateway-catalog-badges">
                      <span className={item.available ? 'tag tag-success' : 'tag tag-neutral'}>
                        {item.available ? 'Disponível' : 'Em breve'}
                      </span>
                      <span className={item.configured ? 'tag tag-active' : 'tag tag-neutral'}>
                        {item.configured ? 'Conectado' : 'Não conectado'}
                      </span>
                    </span>
                  </div>
                ))
              )}
            </div>
          );
        })}
      </div>
      <p className="muted fallback-note">
        Ativação de novos adquirentes é configurada pela plataforma (read-only aqui).
      </p>
    </Card>
  );
}
