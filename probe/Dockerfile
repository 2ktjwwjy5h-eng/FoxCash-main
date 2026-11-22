# dr-probe — medição de latência cross-região para o T3.1 da Spec 135.
# Contêiner descartável (postgres:18-alpine — tem pg_isready/psql) que mede a
# conectividade e a latência do banco primário (sfo) a partir da região onde
# este serviço rodar (us-east/eu-west...). É DELETADO após a medição.
FROM postgres:18-alpine
RUN apk add --no-cache bash
COPY probe.sh /opt/probe.sh
RUN chmod +x /opt/probe.sh
ENTRYPOINT ["/opt/probe.sh"]
