// Seção reutilizável da tela de Config fina (Spec 100 D4): título, campos
// (children), botão "Salvar seção" com banner de sucesso/erro (Spec 73) e
// estado de loading. Cada seção faz PUT parcial da config (Spec 98).

import { useState, type ReactNode } from 'react';
import { ErrorBanner, SuccessBanner } from './ui';

export interface ConfigSectionProps {
  title: string;
  description?: string;
  /** Renderiza os campos da seção. */
  children: ReactNode;
  /** Executa o PUT parcial da seção; resolve ao salvar (banner de sucesso). */
  onSave: () => Promise<void>;
  /** Erro externo (ex.: 403 sem permissão) exibido no lugar do banner interno. */
  externalError?: unknown;
}

export default function ConfigSection({ title, description, children, onSave, externalError }: ConfigSectionProps) {
  const [busy, setBusy] = useState(false);
  const [feedback, setFeedback] = useState<string | null>(null);
  const [error, setError] = useState<unknown>(null);

  const handleSave = async () => {
    setBusy(true);
    setError(null);
    setFeedback(null);
    try {
      await onSave();
      setFeedback('Seção salva.');
    } catch (err) {
      setError(err);
    } finally {
      setBusy(false);
    }
  };

  return (
    <section className="card config-section">
      <h2 className="card-title">{title}</h2>
      {description && <p className="muted config-description">{description}</p>}
      {children}
      <SuccessBanner message={feedback} />
      <ErrorBanner error={error || externalError} />
      <div className="section-actions">
        <button type="button" className="btn btn-primary" onClick={handleSave} disabled={busy}>
          {busy ? 'Salvando…' : 'Salvar seção'}
        </button>
      </div>
    </section>
  );
}
