// Editor da grade de botões inline do broadcast (Spec 80/D3/D8): linhas ×
// botões, cada um com tipo Link (URL) ou Callback (bot:comando). Limites da
// plataforma do bot (Telegram <=100 botões/<=8 por linha; Discord <=5) com
// aviso ao exceder. Monta a estrutura {rows: [[{label, url?|callback?}]]}
// que o backend (Spec 79) valida no POST.

import type { BotPlatform, ButtonGrid, ButtonRow } from './types';

const LIMITS: Record<BotPlatform, { total: number; perRow: number }> = {
  TELEGRAM: { total: 100, perRow: 8 },
  DISCORD: { total: 5, perRow: 5 },
};

export function ButtonsGridEditor({
  platform,
  rows,
  onChange,
  error,
}: {
  platform: BotPlatform | null;
  rows: ButtonGrid;
  onChange: (rows: ButtonGrid) => void;
  error?: string;
}) {
  const limits = platform ? LIMITS[platform] : null;
  const total = rows.reduce((n, r) => n + r.length, 0);
  const atLimit = limits !== null && total >= limits.total;

  const addButton = (rowIndex: number) => {
    if (!limits || atLimit) return;
    const row = rows[rowIndex];
    if (row.length >= limits.perRow) return;
    onChange(
      rows.map((r, i) =>
        i === rowIndex ? [...r, { label: '', type: 'link' as const, url: '', callback: '' }] : r,
      ),
    );
  };

  const removeButton = (rowIndex: number, btnIndex: number) => {
    onChange(
      rows
        .map((r, i) => (i === rowIndex ? r.filter((_, b) => b !== btnIndex) : r))
        .filter((r) => r.length > 0),
    );
  };

  const updateButton = (
    rowIndex: number,
    btnIndex: number,
    patch: Partial<ButtonRow[number]>,
  ) => {
    onChange(
      rows.map((r, i) =>
        i === rowIndex ? r.map((b, j) => (j === btnIndex ? { ...b, ...patch } : b)) : r,
      ),
    );
  };

  const addRow = () => {
    if (!limits || atLimit) return;
    onChange([...rows, [{ label: '', type: 'link' as const, url: '', callback: '' }]]);
  };

  const removeRow = (rowIndex: number) => onChange(rows.filter((_, i) => i !== rowIndex));

  return (
    <div className="bc-buttons">
      <div className="bc-buttons-head">
        <span className="field-label">Botões inline</span>
        <span className="muted">
          {limits
            ? `${platform} · até ${limits.total} botões, ${limits.perRow} por linha · ${total}/${limits.total}`
            : 'Selecione o bot para definir os limites'}
        </span>
      </div>
      {rows.map((row, ri) => (
        <div key={ri} className="bc-button-row">
          <span className="bc-button-row-num">Linha {ri + 1}</span>
          {row.map((button, bi) => (
            <div key={bi} className="bc-button">
              <input
                className="input input-small"
                aria-label={`Rótulo do botão ${ri + 1}.${bi + 1}`}
                placeholder="Rótulo"
                maxLength={80}
                value={button.label}
                onChange={(e) => updateButton(ri, bi, { label: e.target.value })}
              />
              <select
                className="input input-small"
                aria-label={`Tipo do botão ${ri + 1}.${bi + 1}`}
                value={button.type}
                onChange={(e) => {
                  const type = e.target.value as 'link' | 'callback';
                  updateButton(ri, bi, {
                    type,
                    url: type === 'link' ? '' : button.url,
                    callback: type === 'callback' ? '' : button.callback,
                  });
                }}
              >
                <option value="link">Link</option>
                <option value="callback">Callback</option>
              </select>
              {button.type === 'link' ? (
                <input
                  className="input input-small"
                  aria-label={`URL do botão ${ri + 1}.${bi + 1}`}
                  placeholder="https://…"
                  value={button.url}
                  onChange={(e) => updateButton(ri, bi, { url: e.target.value })}
                />
              ) : (
                <input
                  className="input input-small"
                  aria-label={`Callback do botão ${ri + 1}.${bi + 1}`}
                  placeholder="bot:comando"
                  value={button.callback}
                  onChange={(e) => updateButton(ri, bi, { callback: e.target.value })}
                />
              )}
              <button
                type="button"
                className="btn btn-small btn-danger"
                aria-label={`Remover botão ${ri + 1}.${bi + 1}`}
                onClick={() => removeButton(ri, bi)}
              >
                ✕
              </button>
            </div>
          ))}
          <div className="bc-button-row-actions">
            <button
              type="button"
              className="btn btn-small"
              disabled={!limits || atLimit || row.length >= limits.perRow}
              onClick={() => addButton(ri)}
            >
              + Botão
            </button>
            <button type="button" className="btn btn-small btn-danger" onClick={() => removeRow(ri)}>
              Remover linha
            </button>
          </div>
        </div>
      ))}
      <button
        type="button"
        className="btn btn-small"
        disabled={!limits || atLimit}
        onClick={addRow}
      >
        + Linha
      </button>
      {atLimit && (
        <div className="alert alert-warning" role="status">
          Limite de botões desta plataforma atingido.
        </div>
      )}
      {error && (
        <div className="alert alert-error" role="alert">
          {error}
        </div>
      )}
    </div>
  );
}
