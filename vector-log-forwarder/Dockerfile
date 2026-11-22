# Vector Log Forwarder — FoxCash → Axiom (Spec 95, opção A — drain da plataforma)
#
# Imagem oficial do Vector com o config montado no caminho que o serviço
# Railway espera (/etc/vector/vector.toml). O startCommand do serviço é:
#   sh -c 'sed -i "s|__AXIOM_API_TOKEN__|$AXIOM_API_TOKEN|" /etc/vector/vector.toml && exec vector --config /etc/vector/vector.toml'
#
# O sed injeta o token do Axiom no config no boot (o Vector v0.57 NÃO interpola
# ${VAR} em strings TOML — nem em uri/address, nem em token). O placeholder
# __AXIOM_API_TOKEN__ no vector.toml é substituído pela env AXIOM_API_TOKEN;
# o token em si nunca fica no repositório.

FROM timberio/vector:latest-alpine

COPY vector.toml /etc/vector/vector.toml

# Healthcheck opcional: o Vector responde na porta 8686 (api) por padrão.
EXPOSE 8080
