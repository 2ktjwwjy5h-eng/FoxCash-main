#!/usr/bin/env bash
# ============================================================================
# check-docs-consistency.sh — Spec 138 (C2/C3/C5)
#
# Valida a consistência da documentação com o código e FALHA o build (exit 1)
# se qualquer regra abaixo for violada. A documentação passa a ser verificada
# mecanicamente no CI, não por boa vontade.
#
# Regras:
#   A) Tabela do roadmap == frontmatter das specs
#      Para CADA linha da tabela (`| N | ... | status | refs |`) com link para
#      `specs/<dir>/requirements.md`: o status da tabela (4ª coluna) DEVE ser
#      igual ao `status:` do frontmatter do requirements.md linkado.
#      (Resolve os casos 48/49 com dupla entrada comparando pelo DIR linkado,
#      não apenas pelo número.)
#
#   B) Spec com status `verified` NÃO pode ter código
#      O código referencia specs pelos comentários "Spec N" (convenção do
#      projeto). Se uma spec `verified` (não implementada) tiver qualquer
#      arquivo em src/, portal/src, console/src, bots/ ou operator-bot/
#      citando "Spec N", a spec está implementada mas com status atrasado.
#
#   C) Spec `implemented`/`audited` com referência de classe inexistente
#      Classes próprias do projeto (`Foo.java`) citadas no **requirements.md**
#      (o CONTRATO) de specs implementadas/auditadas DEVEM existir em
#      src/. Design.md/task.md são planos/verificações (podem desviar do código
#      final) — não validados. Framework/3rd-party → allowlist — nunca falham.
#
# Allowlist (exceções documentadas): scripts/check-docs-consistency.allowlist
#   Formato: `<spec N>|<tipo>|<motivo>`  (tipo: table|verified-code|missing-class)
#   Ex.:    `17|missing-class|ClasseFooRenameada no refactor da Spec X`
#
# Uso: scripts/check-docs-consistency.sh   (exit 0 = consistente)
# ============================================================================
set -u

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ALLOWLIST_FILE="$ROOT/scripts/check-docs-consistency.allowlist"
FAILURES=0

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------
fail() { echo "FAIL: $1"; FAILURES=$((FAILURES + 1)); }

# frontmatter_status <spec_dir> -> imprime o status do requirements.md
frontmatter_status() {
  local req="$1/requirements.md"
  [ -f "$req" ] || { echo "NO_REQ"; return; }
  sed -n 's/^status:[[:space:]]*\([A-Za-z-]*\).*/\1/p' "$req" | head -1
}

# is_allowlisted <spec_num> <tipo>
is_allowlisted() {
  [ -f "$ALLOWLIST_FILE" ] || return 1
  grep -qE "^$1\|$2(\||$)" "$ALLOWLIST_FILE" 2>/dev/null
}

# has_code_reference <spec_num> — arquivos em src/ citando "Spec N"
has_code_reference() {
  local n="$1"
  grep -rlE "Spec ${n}([^0-9]|$)" \
    "$ROOT/src" "$ROOT/portal/src" "$ROOT/console/src" "$ROOT/bots" "$ROOT/operator-bot" \
    2>/dev/null | grep -v "^$ROOT/management/" | head -1
}

# ---------------------------------------------------------------------------
# Checagem A — Tabela do roadmap == frontmatter
# ---------------------------------------------------------------------------
echo "[check A] Tabela do roadmap vs frontmatter das specs..."
# process substitution (< <(...)): dispensa temp file — /tmp pode não ser
# gravável (ex.: Git Bash no Windows) e TMPDIR/mktemp podem apontar para dir
# inexistente. NÃO é pipe: o while roda no shell principal, então o contador
# de falhas (FAILURES) propaga normalmente.
while IFS='|' read -r num tstatus dir; do
  [ -z "$dir" ] && continue
  [ -d "$ROOT/management/specs/$dir" ] || { fail "Spec $num: dir linkado inexistente specs/$dir"; continue; }
  fm=$(frontmatter_status "$ROOT/management/specs/$dir")
  [ "$fm" = "NO_REQ" ] && { fail "Spec $num: requirements.md ausente em specs/$dir"; continue; }
  if [ "$fm" != "$tstatus" ]; then
    if ! is_allowlisted "$num" "table"; then
      fail "Spec $num: tabela do roadmap='$tstatus' mas frontmatter='$fm' ($dir)"
    fi
  fi
done < <(awk -F'|' '
/^\| [0-9]+ \|/ {
  num = $2; gsub(/ /, "", num)
  status = $4; gsub(/^ +| +$/, "", status)
  if (match($0, /specs\/[0-9]+-[^) ]+\/requirements\.md/)) {
    dir = substr($0, RSTART+6, RLENGTH-6-16)   # sem "specs/" e sem "/requirements.md"
    print num "|" status "|" dir
  }
}' "$ROOT/management/roadmap.md")
echo "  (check A concluído)"

# ---------------------------------------------------------------------------
# Checagem B — Spec `verified` sem código
# ---------------------------------------------------------------------------
echo "[check B] Specs 'verified' sem código no src/..."
for d in "$ROOT"/management/specs/*/; do
  num=$(basename "$d" | sed -E 's/^([0-9]+)-.*/\1/')
  fm=$(frontmatter_status "${d%/}")
  [ "$fm" = "verified" ] || continue
  hit=$(has_code_reference "$num")
  if [ -n "$hit" ]; then
    if ! is_allowlisted "$num" "verified-code"; then
      fail "Spec $num: status 'verified' mas código existe ($hit)"
    fi
  fi
done
echo "  (check B concluído)"

# ---------------------------------------------------------------------------
# Checagem C — Spec implementada/auditada com classe própria inexistente
# ---------------------------------------------------------------------------
echo "[check C] Classes próprias referenciadas por specs implementadas/auditadas..."
for d in "$ROOT"/management/specs/*/; do
  num=$(basename "$d" | sed -E 's/^([0-9]+)-.*/\1/')
  fm=$(frontmatter_status "${d%/}")
  case "$fm" in
    implemented|audited) ;;
    *) continue ;;
  esac
  # classes .java próprias citadas no CONTRATO (requirements.md)
  classes=$(grep -ohE "\b[A-Z][A-Za-z0-9_]*\.java\b" "$d"requirements.md 2>/dev/null | sort -u)
  [ -z "$classes" ] && continue
  for cls in $classes; do
    base="${cls%.java}"
    # classes de framework/3rd-party: não começam com prefixo próprio de pacote
    # — a convenção do projeto é citar a classe simples; checamos existência
    # em src/ e a allowlist para o resto.
    if ! find "$ROOT/src" -name "${base}.java" 2>/dev/null | grep -q .; then
      if ! is_allowlisted "$num" "missing-class"; then
        fail "Spec $num: classe referenciada '${cls}' não existe em src/ (status=$fm)"
      fi
    fi
  done
done
echo "  (check C concluído)"

# ---------------------------------------------------------------------------
# Resultado
# ---------------------------------------------------------------------------
if [ "$FAILURES" -gt 0 ]; then
  echo ""
  echo "docs-consistency: $FAILURES falha(s) — corrija a documentação ou adicione a"
  echo "allowlist documentada em scripts/check-docs-consistency.allowlist"
  exit 1
fi
echo ""
echo "docs-consistency: OK — documentação consistente com o código."
exit 0
