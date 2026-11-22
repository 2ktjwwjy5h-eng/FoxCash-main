# Spec 56 — Task: compression de resposta

- [ ] 1. Adicionar bloco `server.compression` ao `application.yml`
      (`enabled: true`, mime-types JSON/XML/texto, `min-response-size: 1024`).
- [ ] 2. Validar ao vivo (app dev): `curl -H "Accept-Encoding: gzip"` em rota
      JSON → `content-encoding: gzip` + `vary: accept-encoding`; rota <1KB →
      sem compressão; sem header → sem compressão.
- [ ] 3. Teste de integração/smoke registrando a evidência (se viável com o
      mecanismo de teste atual).
- [ ] 4. `mvn test` verde (sem regressão).
- [ ] 5. Deploy em Railway e verificação ao vivo: resposta JSON comprimida em
      produção.
