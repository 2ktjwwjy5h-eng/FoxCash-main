/** Cliente Cloudflare — usado pelo `!bloquear <ip>` (Spec 67, token escopado). */

const CF_API = 'https://api.cloudflare.com/client/v4';

export interface CloudflareClient {
  /** Adiciona regra WAF custom de block por IP (zona). Devolve o id da regra. */
  blockIp(ip: string): Promise<{ ruleId: string; description: string }>;
  /** Limites atuais do edge rate limit (ruleset http_ratelimit). */
  edgeRateLimits(): Promise<{ description: string; expression: string }[]>;
}

function isIpv4(ip: string): boolean {
  return /^(\d{1,3}\.){3}\d{1,3}$/.test(ip)
    && ip.split('.').every((o) => Number(o) >= 0 && Number(o) <= 255);
}

function isIpv6(ip: string): boolean {
  return /^[0-9a-fA-F:]+$/.test(ip) && ip.includes(':');
}

export function validIp(ip: string): boolean {
  return isIpv4(ip) || isIpv6(ip);
}

export function cloudflareClient(token: string, zoneId: string): CloudflareClient {
  const api = async (path: string, init?: RequestInit): Promise<any> => {
    const res = await fetch(`${CF_API}${path}`, {
      ...init,
      headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
    });
    const body = await res.json().catch(() => ({}));
    if (!res.ok || body.success === false) {
      throw new Error(`Cloudflare ${res.status}: ${JSON.stringify(body.errors ?? body)}`);
    }
    return body.result;
  };

  return {
    async blockIp(ip) {
      const description = `operator-bot block ${ip} ${new Date().toISOString().slice(0, 10)}`;
      const phasePath = `/zones/${zoneId}/rulesets/phases/http_request_firewall_custom/entrypoint`;
      // API Token NÃO permite `POST .../entrypoint/rules` (405/10405) — validado
      // contra a API real: lê o entrypoint e reescreve a lista via PUT.
      const current = await api(phasePath).catch(() => ({ rules: [] }));
      const rules = Array.isArray(current?.rules) ? current.rules : [];
      const newRule = {
        description,
        // Sem aspas no IP — `(ip.src eq "1.2.3.4")` falha no parse (20127).
        expression: `(ip.src eq ${ip})`,
        action: 'block',
        enabled: true,
      };
      const updated = await api(phasePath, {
        method: 'PUT',
        body: JSON.stringify({ rules: [...rules, newRule] }),
      });
      const created = (updated?.rules ?? []).find((r: any) => r.description === description);
      return { ruleId: created?.id ?? 'n/d', description };
    },
    async edgeRateLimits() {
      const ruleset = await api(`/zones/${zoneId}/rulesets/phases/http_ratelimit/entrypoint`);
      return (ruleset.rules ?? []).map((r: any) => ({
        description: r.description,
        expression: r.expression,
      }));
    },
  };
}
