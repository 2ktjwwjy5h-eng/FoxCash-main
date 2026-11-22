import { afterEach, describe, expect, it, vi } from 'vitest';
import { fetchWithRetry } from '../src/commands.js';

describe('fetchWithRetry', () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('re-tenta em erro de rede e devolve a resposta no sucesso', async () => {
    const fetchMock = vi.spyOn(global, 'fetch')
      .mockRejectedValueOnce(new TypeError('fetch failed'))
      .mockResolvedValueOnce({ ok: true, status: 200 } as Response);
    const res = await fetchWithRetry('http://x/', undefined, 2);
    expect(res.ok).toBe(true);
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });

  it('lança depois de esgotar as tentativas', async () => {
    const fetchMock = vi.spyOn(global, 'fetch').mockRejectedValue(new TypeError('fetch failed'));
    await expect(fetchWithRetry('http://x/', undefined, 2)).rejects.toThrow('fetch failed');
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });

  it('NÃO re-tenta quando o servidor responde status HTTP (401/500 são respostas válidas)', async () => {
    const fetchMock = vi.spyOn(global, 'fetch').mockResolvedValue({ ok: false, status: 401 } as Response);
    const res = await fetchWithRetry('http://x/', undefined, 3);
    expect(res.status).toBe(401);
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it('NÃO re-tenta em timeout (servidor de pé mas lento — retry só multiplica a espera)', async () => {
    const timeoutErr = Object.assign(
      new Error('The operation was aborted due to timeout'),
      { name: 'TimeoutError' },
    );
    const fetchMock = vi.spyOn(global, 'fetch').mockRejectedValue(timeoutErr);
    await expect(fetchWithRetry('http://x/', undefined, 3)).rejects.toThrow('timeout');
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it('aceita timeoutMs personalizado (lookup de IP com muitos lojistas)', async () => {
    const fetchMock = vi.spyOn(global, 'fetch').mockResolvedValue({ ok: true, status: 200 } as Response);
    const res = await fetchWithRetry('http://x/', undefined, 2, 30_000);
    expect(res.ok).toBe(true);
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });
});
