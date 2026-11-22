---
name: Versionamento inicial (commit)
status: audited
references:
  - "management/lessons.md#L2"
  - "management/specs/2-scaffold-verification/requirements.md"
---

# Spec 3 — Versionamento inicial (commit)

## O que entrega

Um ponto de restauração seguro do projeto: todo o scaffold (código, testes,
config, `management/`) versionado no git, com histórico limpo a partir da
branch `main`.

## Critérios de aceite

1. `git status` não mostra arquivos de trabalho relevantes `untracked`
   (exceto artefatos ignorados por `.gitignore`: `target/`, `.dscode/`, `.env`).
2. Existe pelo menos um commit na branch `main` com mensagem descritiva
   (ex.: `feat: scaffold do backend FoxCash (hexagonal, Spring Boot 3.3)`).
3. `git log` mostra o histórico do commit inicial; `git status` limpo.

## Dependências

- Spec 2 (build e testes verdes antes de versionar).

## Observações

- `.dscode/` contém estado/runtime local (DBs, logs) e **não** deve ser
  commitado; verificar `.gitignore`.
- O commit inicial cobre código de produção, testes e `management/`.
