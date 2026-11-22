// Barra de modos do editor de broadcast (Spec 80/D3): Agora / Agendada /
// Bem-vindos / Repost. Campos condicionais por modo: datetime-local
// (Agendada — validado no futuro) e intervalo 12h/24h/personalizado >=1h
// (Repost).

import type { BroadcastMode } from './types';
import { MIN_REPOST_INTERVAL_SECONDS, MODE_LABELS } from './useBroadcastForm';

const TWELVE_HOURS = 12 * 3600;
const TWENTY_FOUR_HOURS = 24 * 3600;

export function ModeBar({
  mode,
  onChange,
  scheduledAt,
  onScheduledAt,
  intervalSeconds,
  onInterval,
  errors,
}: {
  mode: BroadcastMode;
  onChange: (mode: BroadcastMode) => void;
  scheduledAt: string;
  onScheduledAt: (value: string) => void;
  intervalSeconds: number | null;
  onInterval: (seconds: number | null) => void;
  errors: { scheduledAt?: string; interval?: string };
}) {
  const modes: BroadcastMode[] = ['NOW', 'SCHEDULED', 'WELCOME', 'REPOST'];
  return (
    <div className="bc-modebar">
      <div className="bc-mode-buttons" role="group" aria-label="Modo de envio">
        {modes.map((m) => (
          <button
            key={m}
            type="button"
            className={mode === m ? 'btn btn-small btn-primary' : 'btn btn-small'}
            aria-pressed={mode === m}
            onClick={() => onChange(m)}
          >
            {MODE_LABELS[m]}
          </button>
        ))}
      </div>
      {mode === 'SCHEDULED' && (
        <label className="field">
          <span className="field-label">Data e hora</span>
          <input
            type="datetime-local"
            className="input"
            value={scheduledAt}
            onChange={(e) => onScheduledAt(e.target.value)}
          />
          {errors.scheduledAt && (
            <span className="field-error" role="alert">
              {errors.scheduledAt}
            </span>
          )}
        </label>
      )}
      {mode === 'REPOST' && (
        <div className="bc-repost">
          <label className="field">
            <span className="field-label">Intervalo</span>
            <select
              className="input"
              aria-label="Intervalo"
              value={
                intervalSeconds === TWELVE_HOURS
                  ? '12h'
                  : intervalSeconds === TWENTY_FOUR_HOURS
                    ? '24h'
                    : 'custom'
              }
              onChange={(e) => {
                const v = e.target.value;
                onInterval(v === '12h' ? TWELVE_HOURS : v === '24h' ? TWENTY_FOUR_HOURS : 3600);
              }}
            >
              <option value="12h">12 horas</option>
              <option value="24h">24 horas</option>
              <option value="custom">Personalizado…</option>
            </select>
          </label>
          {intervalSeconds !== TWELVE_HOURS && intervalSeconds !== TWENTY_FOUR_HOURS && (
            <label className="field">
              <span className="field-label">Horas (mín. 1)</span>
              <input
                type="number"
                min={1}
                className="input"
                value={Math.max(1, Math.floor((intervalSeconds || MIN_REPOST_INTERVAL_SECONDS) / 3600))}
                onChange={(e) => {
                  const hours = Math.max(1, Number(e.target.value) || 1);
                  onInterval(hours * 3600);
                }}
              />
            </label>
          )}
          {errors.interval && (
            <span className="field-error" role="alert">
              {errors.interval}
            </span>
          )}
        </div>
      )}
    </div>
  );
}
