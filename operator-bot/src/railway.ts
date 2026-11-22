import { execFile } from 'node:child_process';
import { promisify } from 'node:util';

const execFileAsync = promisify(execFile);

/** Resultado normalizado de `railway status`. */
export interface RailwayStatus {
  online: boolean;
  deploying?: boolean;
  replicas?: string;
  deploymentId?: string;
  url?: string;
}

export interface RailwayClient {
  status(): Promise<RailwayStatus>;
  logs(lines: number): Promise<string>;
}

const API = 'https://backboard.railway.app/graphql/v2';
const ACTIVE = new Set(['BUILDING', 'DEPLOYING', 'INITIALIZING', 'QUEUED', 'WAITING']);

/**
 * Cliente via API GraphQL do Railway (produção/container) — requer RAILWAY_TOKEN
 * (token de API criado no dashboard: Account Settings → Tokens). O container
 * injeta RAILWAY_SERVICE_ID/RAILWAY_ENVIRONMENT_ID/RAILWAY_PROJECT_ID.
 */
function apiClient(token: string): RailwayClient {
  const graphql = async (query: string, variables: Record<string, unknown>): Promise<any> => {
    const res = await fetch(API, {
      method: 'POST',
      headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
      body: JSON.stringify({ query, variables }),
    });
    const body = await res.json().catch(() => ({}));
    if (body.errors?.length) {
      throw new Error(`Railway API: ${body.errors.map((e: any) => e.message).join('; ')}`);
    }
    return body.data;
  };

  // Alvo das consultas (status/logs): o serviço OPERADO (backend FoxCash) por
  // padrão via RAILWAY_OPS_SERVICE_ID; sem ele, o próprio serviço do bot.
  const serviceId = process.env.RAILWAY_OPS_SERVICE_ID ?? process.env.RAILWAY_SERVICE_ID ?? '';
  const envId = process.env.RAILWAY_ENVIRONMENT_ID;

  const latestDeployment = async () => {
    const vars: Record<string, unknown> = { serviceId, envId: envId ?? null };
    const data = await graphql(
      `query($serviceId: String!, $envId: String) {
        deployments(input: { serviceId: $serviceId, environmentId: $envId }, first: 1) {
          edges { node { id status url } }
        }
      }`,
      vars,
    );
    return data?.deployments?.edges?.[0]?.node as
      | { id?: string; status?: string; url?: string | null }
      | undefined;
  };

  return {
    async status() {
      const node = await latestDeployment();
      if (!node) return { online: false };
      return {
        online: node.status === 'SUCCESS',
        deploying: !!node.status && ACTIVE.has(node.status),
        deploymentId: node.id,
        url: node.url ?? undefined,
      };
    },
    async logs(lines) {
      // Varre os últimos 3 deployments (o backend redeploya a cada push; um
      // bloqueio/investigação pode estar num deployment já substituído).
      const data = await graphql(
        `query($serviceId: String!, $envId: String) {
          deployments(input: { serviceId: $serviceId, environmentId: $envId }, first: 3) {
            edges { node { id } }
          }
        }`,
        { serviceId, envId: envId ?? null },
      );
      const ids = (data?.deployments?.edges ?? [])
        .map((e: any) => e.node?.id as string | undefined)
        .filter(Boolean) as string[];
      if (ids.length === 0) return '';
      const perDeploy = Math.max(200, Math.min(Math.ceil(lines / ids.length), 2000));
      const chunks: string[] = [];
      for (const id of ids) {
        const l = await graphql(
          `query($deploymentId: String!, $limit: Int!) {
            deploymentLogs(deploymentId: $deploymentId, limit: $limit) { message }
          }`,
          { deploymentId: id, limit: perDeploy },
        );
        chunks.push(...(l?.deploymentLogs ?? []).map((x: any) => x.message ?? ''));
      }
      return chunks.join('\n');
    },
  };
}

/** Cliente via CLI local (dev — binário `railway` autenticado no PATH). */
function cliClient(cwd: string): RailwayClient {
  const run = async (args: string[]) => {
    try {
      const r = await execFileAsync('railway', args, {
        cwd,
        maxBuffer: 4 * 1024 * 1024,
      });
      return r.stdout;
    } catch (e) {
      const err = e as NodeJS.ErrnoException;
      // CLI ausente (container do Railway; Windows/git-bash falha no shim npm)
      // → mensagem clara, sem stack crua.
      if (err.code === 'ENOENT' || err.code === 'EINVAL') {
        throw new Error('CLI railway não disponível neste ambiente (instale o CLI ou configure RAILWAY_TOKEN)');
      }
      throw e;
    }
  };

  return {
    async status() {
      const out = await run(['status']);
      const online = out.includes('Online');
      const deploying = out.includes('Deploying') || out.includes('Building');
      const replicas = /replicas:\s+([\d/]+)/.exec(out)?.[1];
      const deploymentId = /deployment ID:\s+(\S+)/.exec(out)?.[1];
      const url = /url:\s+(\S+)/.exec(out)?.[1];
      return { online, deploying, replicas, deploymentId, url };
    },
    async logs(lines) {
      return run(['logs', '--lines', String(lines)]);
    },
  };
}

export function railwayClient(options: { cwd?: string } = {}): RailwayClient {
  // Produção/container: RAILWAY_TOKEN + IDs injetados → API GraphQL.
  if (process.env.RAILWAY_TOKEN) return apiClient(process.env.RAILWAY_TOKEN);
  // Dev local: CLI autenticada (fallback).
  return cliClient(options.cwd ?? process.cwd());
}
