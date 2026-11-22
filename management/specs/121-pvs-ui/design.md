---
name: PVS UI portal — pagar Pix Copia e Cola com o saldo
status: audited
---

# Spec 121 — Design: PVS UI portal (pagamento via saldo)

## D1 — Conceito

O `WithdrawPage.tsx` (Spec 73/70) ganha uma **seção irmã do saque**: "Pagamento
via Saldo". Mesma tela, mesmo fluxo mental ("dinheiro saindo do saldo"), **sem
rota nova** — o PVS é uma variação do saque, então convive com o formulário de
saque existente (com destino), o card de saque automático (Spec 70) e a
listagem de saques. Nenhum comportamento existente muda.

### D1.1 Decisões do usuário (2026-08-15) → UI

| Decisão | Regra de UI |
|---|---|
| Só Pix Copia e Cola | um único campo textarea para colar o brcode |
| Só Pix **estático** | aviso claro se o backend responder `pvs_dynamic_pix_not_supported` |
| Taxa R$ 2,50 | prévia sempre mostra valor + taxa + total **antes** do pagar |
| Teto diário + gates | card de limites (usado/restante + teto por operação) sempre visível |
| 2 specs | 121 = só o UI sobre os endpoints da 120 |

## D2 — Estrutura no WithdrawPage

O arquivo atual tem: formulário de saque (`amount`/`destinationId`), card de
saque automático e a listagem `withdrawals`. Adicionar, **sem reestruturar o
existente**:

```tsx
// Estado novo
const [pixCode, setPixCode] = useState('');
const [pvsAmount, setPvsAmount] = useState('');        // editável só quando EMV sem valor
const [pvsPreview, setPvsPreview] = useState<PvsPreview | null>(null); // {keyMask, amount, fee, total}
const [pvsError, setPvsError] = useState<unknown>(null);
const [pvsSuccess, setPvsSuccess] = useState<...>(null);
const [pvsSubmitting, setPvsSubmitting] = useState(false);
const [pvsConfirmOpen, setPvsConfirmOpen] = useState(false);

const pvsLimits = useAsync<PvsLimits>(() => api.get('/api/v1/portal/pvs/limits'), []);
```

Layout (cards na ordem visual: **Pagamento via Saldo** acima do card de saque,
abaixo do saldo):

1. `Card "Pagamento via Saldo"`:
   - `Field textarea#pvs-pix-code` — "Pix Copia e Cola (cole o código)".
   - Validação leve no cliente: não-vazio; se não começa com `000201`, aviso
     "Parece que o código não é um Pix Copia e Cola".
   - Botão **"Ver prévia"** → monta a prévia (ver D3).
2. `Card "Prévia do pagamento"` (visível quando `pvsPreview`):
   - Destino: chave mascarada (`maskKey`).
   - Valor: editável **se o EMV não carrega valor** (readonly + badge "do Pix"
     se carrega), sempre `> 0`.
   - Taxa: R$ 2,50 (`formatMoney(2.5)` — mesmo valor do saque; o backend
     decide).
   - **Total** = valor + taxa.
   - Botão **"Pagar R$ X"** → abre `ConfirmDialog` (Spec 73) com o total.
3. `Card "Limites do dia"` (do `GET /pvs/limits`):
   - Teto diário (ex.: R$ 5.000,00), **usado hoje**, **restante** (cap − used,
     floor 0), teto por operação.
   - Barra de progresso usada/limite (Spec 73 padrão) — aviso quando usado ≥
     80% do cap.

## D3 — Prévia e fluxo de pagamento

**Prévia** (cliente): sem parser no front (o parser é do backend — D2 da 120);
o botão "Ver prévia" chama **`GET /pvs/limits`** para conferir o restante e
exibe valor digitado + taxa 2.50 + total, com a chave **mascarada** (função
`maskKey` igual à usada na listagem de saques — `529***725`). Se o total >
restante, desabilita o botão Pagar com aviso "Limite diário insuficiente".

**Confirmar** → `POST /api/v1/portal/pvs/pay` `{ pixCode, amount? }`:

```ts
const res = await api.post('/api/v1/portal/pvs/pay', {
  pixCode,
  ...(emvSemValor ? { amount: Number(pvsAmount) } : {}),
});
```

- **200** `{ withdrawalId, amount, fee, walletBalance, status: "PROCESSING" }` →
  `setPvsSuccess(...)` (status PROCESSING + novo saldo), limpa o formulário,
  recarrega saldo e limites e a listagem de saques.
- **Erro** (400/409 com `{ code, message }` do backend) → `ErrorBanner` com
  mensagem mapeada (D4); se `code` é de teto → recarrega `pvsLimits`.

## D4 — Mapa de erros (backend Spec 120 → mensagem de UI)

| code | Mensagem |
|---|---|
| `pvs_invalid_code` | "Código Pix inválido. Confira o Copia e Cola." |
| `pvs_dynamic_pix_not_supported` | "Pix dinâmico não é suportado no Pagamento via Saldo — use um Pix de chave (estático)." |
| `pvs_unsupported_key` | "A chave Pix desse código não é reconhecida (CPF, CNPJ, telefone ou chave aleatória)." |
| `pvs_amount_required` | "Esse Pix não informa o valor — digite o valor a pagar." |
| `pvs_amount_mismatch` | "O valor informado difere do valor do Pix. Confira." |
| `pvs_max_per_payment_exceeded` | "Valor acima do limite por operação." |
| `pvs_daily_cap_exceeded` | "Limite diário de Pagamento via Saldo atingido. Tente amanhã." |
| saldo (erro do saque) | reuso da mensagem existente de saldo insuficiente |

Mapeamento num `Record<code, string>` + fallback para `message` do backend
(consistente com o tratamento de erro do `WithdrawPage`).

## D5 — Arquivos

- `portal/src/pages/WithdrawPage.tsx` — seção nova (modificado; o restante
  intacto — cirurgia mínima, Spec 3).
- `portal/src/components/ui.tsx` — reuso (`Card`, `Field`, `ErrorBanner`,
  `Spinner`, `formatMoney`, `useAsync`) e `portal/src/components/ConfirmDialog.tsx`
  — reuso (`ConfirmDialog`, arquivo separado — Spec 73); **sem componente
  novo** a menos que a complexidade exija (KISS).
- Nenhuma mudança em `apiClient`/`auth`/rotas.

## D6 — Testes

- **Unit/componente** (padrão dos testes do portal, `vitest`): máscara da chave;
  valor readonly quando EMV tem valor vs editável sem valor; botão Pagar
  desabilitado quando total > restante; mapa de erros (cada code → mensagem);
  sucesso limpa o form e recarrega.
- **E2E** (`e2e-tests/tests/portal-ui.spec.js`, padrão Spec 73):
  1. Fluxo feliz: cola brcode → prévia (chave mascarada + total) → confirma →
     PROCESSING → item no histórico.
  2. Pix dinâmico: backend mock responde `pvs_dynamic_pix_not_supported` →
     banner com a mensagem.
  3. Teto: mock responde `pvs_daily_cap_exceeded` → banner + limites recarregados.
- **Regressão**: saque com destino e saque automático continuam funcionando na
  mesma tela (Specs 32/36/70).

**Âncoras validadas na spec:** `WithdrawPage.tsx` existe (formulário de saque +
card auto-saque + listagem; `useAsync`/`api`/`formatMoney`/`ErrorBanner` da Spec
73); `GET /api/v1/merchants/{id}/withdrawals` já lista todos os tipos (backend
não filtra — Spec 120); endpoints `pay`/`limits` da Spec 120; `ConfirmDialog` e
`ErrorBanner` existem no `components/ui` (Spec 73).
