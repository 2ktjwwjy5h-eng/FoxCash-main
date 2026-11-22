---
name: Ambiente de desenvolvimento (toolchain)
status: audited
references:
  - "management/lessons.md#L1"
  - "management/vision.md#V1"
---

# Spec 1 — Ambiente de desenvolvimento (toolchain)

## O que entrega

Um ambiente local mínimo e funcional para compilar, testar e rodar o backend
FoxCash:

- JDK 21 (LTS) instalado e disponível no `PATH` (`java -version` → 21.x).
- Maven 3.9+ instalado e disponível no `PATH` (`mvn -version`).
- PostgreSQL 16+ acessível em `localhost:5432` com banco `nyxcash` (usuário/senha
  `nyxcash`) — instalação nativa do Windows ou, opcionalmente, container via
  `docker compose up -d` (credenciais em `docker-compose.yml`).

## Critérios de aceite

1. `java -version` retorna uma distribuição do JDK 21.
2. `mvn -version` retorna Maven 3.9+ usando o JDK 21.
3. PostgreSQL local acessível em `localhost:5432` (banco `nyxcash`, usuário/senha
   `nyxcash`) — via instalação nativa ou container Docker.
4. Documentar em `README.md` (seção "Pré-requisitos") qualquer passo não óbvio
   de instalação na plataforma atual (Windows/Git Bash).

## Dependências

- Nenhuma (primeira spec da cadeia de fundação).

## Observações

- O scaffold já existe e está completo; esta spec **não** altera código
  de produção, apenas prepara o ambiente para a Spec 2.
