# Spec 56 — Design: compression de resposta

## Decisão

Usar a compressão nativa do Tomcat/Spring Boot (`server.compression.*`) — sem
dependência nova, sem filtro custom, sem alterar o contrato da API (o cliente
controla via `Accept-Encoding`; o servidor responde com `Vary: accept-encoding`).

## Mudanças

### `src/main/resources/application.yml` (bloco `server`)

```yaml
server:
  compression:
    # Spec 56: compressão gzip de respostas (auditoria de produção 2026-08-07 —
    # nenhuma resposta vinha comprimida). JSON é o payload dominante da API.
    enabled: true
    mime-types: application/json,application/xml,text/html,text/plain,text/css,application/javascript
    min-response-size: 1024
```

O default do Tomcat para `server.compression.min-response-size` já é 2048 bytes;
setamos 1024 explicitamente para cobrir respostas intermediárias. O Tomcat
comprime apenas respostas ≥ 1KB e apenas para clientes que enviam
`Accept-Encoding: gzip` (negociação automática do `GzipResponseWrapper`).

Nota: o `forward-headers-strategy: framework` do Railway não interfere — a
compressão acontece no conector Tomcat, antes do edge. Se o edge do Railway
também comprimir, o `Vary: accept-encoding` do app evita dupla compressão
(comportamento padrão de proxies).

## Testes

- Teste de contexto com MockMvc: `GET` em rota JSON com `Accept-Encoding: gzip`
  e resposta > 1KB → header `content-encoding: gzip` presente; resposta < 1KB →
  ausente. (Se o MockMvc não aplicar a compressão do Tomcat real — a compressão
  é do conector —, validar via teste de integração com `SpringBootTest` +
  `TestRestTemplate`/`RestClient` real contra o app dev, ou smoke curl no
  boot local. A task decide a forma mais confiável e registra a evidência.)

## Arquivos

| Arquivo | Ação |
|---|---|
| `src/main/resources/application.yml` | editar bloco `server` (adicionar `compression`) |
| teste de integração/smoke | novo (validação ao vivo) |
