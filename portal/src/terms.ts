// Renderização leve de markdown para o modal de termos (Spec 143).
//
// Cobre APENAS os constructos usados em docs/politicas-uso.md: títulos
// (#/##/###), negrito (**x**), listas (- e aninhadas com 2 espaços) com
// **continuação recuada** (bullet quebrado em várias linhas), hr (---),
// parágrafos e tabelas (| a | b |). O conteúdo é **escapado antes** do markdown
// — o documento nunca renderiza HTML próprio (texto jurídico estático, sem
// confiança no input). Sem dependência de lib: o bundle do portal fica pequeno.

function escapeHtml(s: string): string {
  return s
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;');
}

/** Markdown inline (negrito) sobre texto já escapado. */
function inline(s: string): string {
  return escapeHtml(s).replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>');
}

/** Renderiza o subset de markdown do politicas-uso.md → HTML seguro. */
export function renderMarkdown(src: string): string {
  const lines = src.split('\n');
  const out: string[] = [];
  let table: string[] | null = null;

  // Estado da lista: `inList` ativo, `listNested` (ul aninhado), `liText`
  // acumula o item em curso (inclui continuações recuadas de linha).
  let inList = false;
  let listNested = false;
  let liText: string | null = null;

  const flushListItem = (): void => {
    if (liText !== null) {
      out.push(`<li>${inline(liText)}</li>`);
      liText = null;
    }
  };
  const closeList = (): void => {
    flushListItem();
    if (inList) {
      if (listNested) out.push('</ul>');
      out.push('</ul>');
      inList = false;
      listNested = false;
    }
  };
  const flushTable = (): void => {
    if (!table) return;
    const rows = table.map((row, i) => {
      if (i === 1) return ''; // linha separadora | :--- |
      const cells = row
        .split('|')
        .slice(1, -1)
        .map((c) => c.trim());
      const tag = i === 0 ? 'th' : 'td';
      return `<tr>${cells.map((c) => `<${tag}>${inline(c)}</${tag}>`).join('')}</tr>`;
    });
    out.push(`<table>${rows.join('')}</table>`);
    table = null;
  };

  for (const raw of lines) {
    const line = raw.trimEnd();

    if (/^\s*\|.*\|\s*$/.test(line)) {
      closeList();
      table = table || [];
      table.push(line);
      continue;
    }
    flushTable();

    const heading = line.match(/^(#{1,3})\s+(.*)$/);
    if (heading) {
      closeList();
      const level = heading[1].length;
      out.push(`<h${level}>${inline(heading[2])}</h${level}>`);
      continue;
    }

    if (/^\s*---+\s*$/.test(line)) {
      closeList();
      out.push('<hr />');
      continue;
    }

    const nested = line.match(/^\s{2}- (.*)$/);
    const bullet = line.match(/^- (.*)$/);
    if (bullet || nested) {
      flushListItem();
      if (!inList) {
        inList = true;
        out.push('<ul>');
        listNested = false;
      }
      if (nested && !listNested) {
        out.push('<ul>');
        listNested = true;
      } else if (!nested && listNested) {
        out.push('</ul>');
        listNested = false;
      }
      liText = (nested || bullet)![1];
      continue;
    }

    // Continuação recuada de um item de lista (bullet quebrado em linhas).
    if (inList && liText !== null) {
      const cont = line.match(/^ {2,}(?!- )(.*)$/);
      if (cont) {
        liText += ` ${cont[1]}`;
        continue;
      }
    }

    if (line.trim() === '') {
      closeList();
      continue;
    }

    closeList();
    out.push(`<p>${inline(line)}</p>`);
  }

  flushTable();
  closeList();
  return out.join('\n');
}
