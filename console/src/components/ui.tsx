// Helpers de UI do Master Console (Spec 71): formatação de moeda/data, cards,
// campos, banner de erro e hook de async. Sem lib de UI — CSS puro (styles.css).

import { useCallback, useEffect, useState, type ReactNode, type InputHTMLAttributes } from 'react';
import { ApiError } from '../apiClient';

export function formatMoney(value: string | number | null | undefined): string {
  const n = Number(value ?? 0);
  return n.toLocaleString('pt-BR', { style: 'currency', currency: 'BRL' });
}

export function formatDate(value: string | null | undefined): string {
  if (!value) return '—';
  return new Date(value).toLocaleString('pt-BR');
}

export function Card({ title, children }: { title?: string; children: ReactNode }) {
  return (
    <section className="card">
      {title && <h2 className="card-title">{title}</h2>}
      {children}
    </section>
  );
}

export function Field({ label, ...props }: { label: string } & InputHTMLAttributes<HTMLInputElement>) {
  return (
    <label className="field">
      <span className="field-label">{label}</span>
      <input className="input" {...props} />
    </label>
  );
}

export function ErrorBanner({ error }: { error: unknown }) {
  if (!error) return null;
  const message = error instanceof ApiError ? error.message : error instanceof Error ? error.message : 'Erro inesperado';
  return (
    <div className="alert alert-error" role="alert">
      {message}
    </div>
  );
}

export function SuccessBanner({ message }: { message: string | null }) {
  if (!message) return null;
  return (
    <div className="alert alert-success" role="status">
      {message}
    </div>
  );
}

/** Copia texto para a clipboard; devolve true em sucesso (Spec 73 — copiar com feedback). */
export async function copyToClipboard(text: string): Promise<boolean> {
  try {
    await navigator.clipboard.writeText(text);
    return true;
  } catch {
    return false;
  }
}

export function Spinner() {
  return <div className="spinner" aria-label="carregando" />;
}

/** Busca dados na montagem; re-executa quando `deps` mudam. */
export function useAsync<T>(fn: () => Promise<T>, deps: unknown[]): { data: T | null; error: unknown; loading: boolean; reload: () => void } {
  const [data, setData] = useState<T | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [loading, setLoading] = useState(true);
  const [tick, setTick] = useState(0);

  // eslint-disable-next-line react-hooks/exhaustive-deps
  const run = useCallback(fn, deps);

  useEffect(() => {
    let cancelled = false;
    // Spec 73: em reload (troca de página/filtro) mantém os dados anteriores
    // visíveis — o loading só vira true quando não há dados para mostrar
    // (primeiro carregamento). Evita o "blink" da lista a cada reload.
    setError(null);
    setLoading(data === null);
    run()
      .then((result) => {
        if (!cancelled) setData(result);
      })
      .catch((err) => {
        if (!cancelled) setError(err);
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps -- `data` é intencionalmente não-dep (relê o valor atual do render)
  }, [run, tick]);

  const reload = useCallback(() => setTick((t) => t + 1), []);
  return { data, error, loading, reload };
}

/** Barra de paginação simples (página 0-based; sem totalPages → só botões). */
export function Pagination({
  page,
  pageCount,
  onChange,
}: {
  page: number;
  pageCount: number;
  onChange: (page: number) => void;
}) {
  if (pageCount <= 1) return null;
  return (
    <div className="pagination">
      <button type="button" className="btn btn-small" disabled={page <= 0} onClick={() => onChange(page - 1)}>
        ← Anterior
      </button>
      <span>
        Página {page + 1} de {pageCount}
      </span>
      <button
        type="button"
        className="btn btn-small"
        disabled={page >= pageCount - 1}
        onClick={() => onChange(page + 1)}
      >
        Próxima →
      </button>
    </div>
  );
}
