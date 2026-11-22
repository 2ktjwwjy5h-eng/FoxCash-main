// Comunidade (Spec 119) — avisos/novidades da plataforma, read-only no portal.
// Consome GET /api/v1/portal/community (paginado, pinados primeiro). KISS:
// sem chat/comentários (fora de escopo).

import { useState } from 'react';
import { Card, ErrorBanner, Spinner, useAsync } from '../components/ui';
import { api } from '../apiClient';

interface CommunityPost {
  id: string;
  title: string;
  content: string;
  pinned: boolean;
  publishedAt: string;
}

interface Page<T> {
  items: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

function formatDate(iso: string): string {
  const d = new Date(iso);
  return d.toLocaleDateString('pt-BR', { day: '2-digit', month: '2-digit', year: 'numeric' });
}

export default function CommunityPage() {
  const [page, setPage] = useState(0);
  const [selected, setSelected] = useState<CommunityPost | null>(null);

  const { data, error, loading } = useAsync<Page<CommunityPost>>(
    () => api.get<Page<CommunityPost>>(`/api/v1/portal/community?page=${page}&size=20`),
    [page],
  );

  return (
    <section className="page">
      <h1>Comunidade</h1>
      <p className="muted">Avisos e novidades da plataforma — canal oficial de comunicados.</p>

      <ErrorBanner error={error} />

      {loading && (data?.items ?? []).length === 0 ? (
        <Spinner />
      ) : (
        <div className="community-list">
          {(data?.items ?? []).map((post) => (
            <Card key={post.id}>
              <button
                type="button"
                className="community-post"
                onClick={() => setSelected(post)}
                aria-label={`Abrir: ${post.title}`}
              >
                <div className="community-post-title">
                  {post.pinned && <span className="tag tag-active">PINADO</span>}{' '}
                  <strong>{post.title}</strong>
                </div>
                <div className="muted">
                  {post.content.length > 140 ? `${post.content.slice(0, 140)}…` : post.content}
                </div>
                <div className="muted community-post-date">{formatDate(post.publishedAt)}</div>
              </button>
            </Card>
          ))}
          {(data?.items ?? []).length === 0 && !loading && (
            <div className="empty-state">Nenhum aviso publicado ainda.</div>
          )}
        </div>
      )}

      {(data?.totalPages ?? 0) > 1 && (
        <div className="pagination">
          <button type="button" className="btn btn-small" disabled={page === 0} onClick={() => setPage((p) => p - 1)}>
            Anterior
          </button>
          <span className="muted">Página {page + 1} de {data?.totalPages}</span>
          <button type="button" className="btn btn-small" disabled={page >= (data?.totalPages ?? 1) - 1} onClick={() => setPage((p) => p + 1)}>
            Próxima
          </button>
        </div>
      )}

      {/* Detalhe do post (modal simples, KISS — Spec 119 D7.3). */}
      {selected && (
        <div className="modal-backdrop">
          <div className="modal modal-wide" role="dialog" aria-modal="true" aria-label={selected.title}>
            <div className="crm-chat-header">
              <div>
                <h2 style={{ margin: 0, fontSize: 18 }}>
                  {selected.pinned && <span className="tag tag-active">PINADO</span>} {selected.title}
                </h2>
                <div className="crm-chat-sub">{formatDate(selected.publishedAt)}</div>
              </div>
              <div className="crm-chat-actions">
                <button type="button" className="btn btn-small btn-ghost" onClick={() => setSelected(null)}>
                  Fechar
                </button>
              </div>
            </div>
            <div className="community-post-content" style={{ whiteSpace: 'pre-wrap' }}>
              {selected.content}
            </div>
          </div>
        </div>
      )}
    </section>
  );
}
