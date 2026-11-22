// Inserção de tags dinâmicas no editor de broadcast (Spec 80/D3):
// {primeiro_nome}, {username}, {data} — com dica (title) do que cada uma
// resolve (o bot aplica a tag por destinatário no envio).

import { TAG_INSERTS } from './markup';

export function TagInserter({ onInsert }: { onInsert: (tag: string) => void }) {
  return (
    <div className="bc-tagbar">
      <span className="field-label">Tags dinâmicas</span>
      {TAG_INSERTS.map((t) => (
        <button
          key={t.tag}
          type="button"
          className="btn btn-small"
          title={t.hint}
          aria-label={`Inserir ${t.tag}`}
          onClick={() => onInsert(t.tag)}
        >
          {t.tag}
        </button>
      ))}
    </div>
  );
}
