// Device fingerprint caseiro do portal (Spec 141) — sinal de risco PASSIVO.
//
// Coleta sinais do navegador (canvas, WebGL, fontes, UA, timezone, resolução,
// navigator), normaliza e reduz a um hash SHA-256 hex (64 chars) via Web
// Crypto. O backend valida o formato e associa ao lojista — NUNCA bloqueia.
//
// Best-effort por design: se qualquer sinal falhar (canvas bloqueado, WebGL
// off, crypto.subtle indisponível em HTTP), a coleta continua com o que tem;
// se nada funcionar, retorna '' (o backend ignora hash ausente/inválido).
// O hash é calculado 1x por carregamento da página (cache em módulo) para
// estabilidade durante a sessão.

/** Fontes candidatas testadas via measureText (detecção de fontes instaladas). */
const FONT_PROBES = [
  'monospace', 'sans-serif', 'serif',
  'Arial', 'Helvetica', 'Times New Roman', 'Courier New', 'Verdana',
  'Georgia', 'Tahoma', 'Trebuchet MS', 'Impact', 'Comic Sans MS',
  'Segoe UI', 'Roboto', 'Open Sans', 'Inter', 'Calibri', 'Arial Black',
];

/** String canônica desenhada no canvas (estável entre sessões do mesmo browser). */
const CANVAS_TEXT = 'FoxCash-fingerprint-1385';

let cachedHash: string | null = null;

/** Hash SHA-256 hex (64 chars) do fingerprint — '' quando indisponível. */
export async function getFingerprintHash(): Promise<string> {
  if (cachedHash !== null) {
    return cachedHash;
  }
  try {
    const signals = collectSignals();
    const canonical = JSON.stringify(signals);
    cachedHash = await sha256Hex(canonical);
  } catch {
    cachedHash = '';
  }
  return cachedHash;
}

/** Coleta os sinais do navegador (nunca lança — cada bloco é isolado). */
function collectSignals(): Record<string, unknown> {
  const signals: Record<string, unknown> = {};
  const nav = navigator;

  // Identidade básica do navegador.
  signals.userAgent = safe(() => nav.userAgent);
  signals.language = safe(() => nav.language);
  signals.languages = safe(() => [...(nav.languages ?? [])]);
  signals.platform = safe(() => nav.platform);
  signals.hardwareConcurrency = safe(() => nav.hardwareConcurrency);
  signals.deviceMemory = safe(() => (nav as Navigator & { deviceMemory?: number }).deviceMemory);
  signals.maxTouchPoints = safe(() => nav.maxTouchPoints);
  signals.timezone = safe(() => Intl.DateTimeFormat().resolvedOptions().timeZone);

  // Resolução/tela.
  signals.screen = safe(() => ({
    width: window.screen.width,
    height: window.screen.height,
    colorDepth: window.screen.colorDepth,
    pixelDepth: window.screen.pixelDepth,
    availWidth: window.screen.availWidth,
    availHeight: window.screen.availHeight,
  }));

  // Canvas fingerprint (renderização única do browser/GPU).
  signals.canvas = safe(() => {
    const canvas = document.createElement('canvas');
    canvas.width = 240;
    canvas.height = 60;
    const ctx = canvas.getContext('2d');
    if (!ctx) {
      return 'unavailable';
    }
    ctx.textBaseline = 'alphabetic';
    ctx.fillStyle = '#f60';
    ctx.fillRect(100, 1, 62, 20);
    ctx.fillStyle = '#069';
    ctx.fillText(CANVAS_TEXT, 2, 15);
    ctx.fillStyle = 'rgba(102, 204, 0, 0.7)';
    ctx.fillText(CANVAS_TEXT, 4, 17);
    return canvas.toDataURL();
  });

  // WebGL renderer/vendor (identifica GPU/driver).
  signals.webgl = safe(() => {
    const canvas = document.createElement('canvas');
    const gl = (canvas.getContext('webgl')
      || canvas.getContext('experimental-webgl')) as WebGLRenderingContext | null;
    if (!gl) {
      return 'unavailable';
    }
    const ext = gl.getExtension('WEBGL_debug_renderer_info');
    return {
      renderer: ext ? gl.getParameter(ext.UNMASKED_RENDERER_WEBGL) : gl.getParameter(gl.RENDERER),
      vendor: ext ? gl.getParameter(ext.UNMASKED_VENDOR_WEBGL) : gl.getParameter(gl.VENDOR),
    };
  });

  // Fontes instaladas (largura diferente do fallback = fonte presente).
  signals.fonts = safe(() => {
    const probe = (font: string): number => {
      const span = document.createElement('span');
      span.style.font = `72px ${font}`;
      span.textContent = 'mmmmmmmmmmlli';
      document.body.appendChild(span);
      const width = span.offsetWidth;
      document.body.removeChild(span);
      return width;
    };
    const base = probe('monospace');
    return FONT_PROBES.filter((font) => probe(font) !== base && font !== 'monospace');
  });

  return signals;
}

/** SHA-256 hex do payload (Web Crypto); rejeita se crypto.subtle indisponível. */
async function sha256Hex(payload: string): Promise<string> {
  const data = new TextEncoder().encode(payload);
  const digest = await crypto.subtle.digest('SHA-256', data);
  return Array.from(new Uint8Array(digest))
    .map((b) => b.toString(16).padStart(2, '0'))
    .join('');
}

/** Executa e devolve o valor — {@code undefined} em falha (best-effort). */
function safe<T>(fn: () => T): T | undefined {
  try {
    return fn();
  } catch {
    return undefined;
  }
}
