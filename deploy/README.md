# Deploy do FoxCash em VPS — sem Docker (Spec 9)

> **Nota (2026-08-05, ADR-011):** o caminho de produção **recomendado** passou
> a ser o **Railway** (app + PostgreSQL) — ver [`deploy/railway.md`](railway.md).
> Este runbook de VPS própria permanece válido como **alternativa**
> (`SPRING_PROFILES_ACTIVE=prod` com TLS próprio na 8443; **não** usar o perfil
> `railway` aqui).

Runbook passo a passo para subir o backend em um VPS Linux **sem containers**
(JDK + PostgreSQL nativos + systemd). Pré-requisitos: VPS Ubuntu 22.04/24.04
(x86_64), acesso SSH e (opcional) um domínio apontado para o IP.

---

## 1. JDK 21 (Temurin)

```bash
sudo apt-get update
sudo apt-get install -y wget apt-transport-https
# Adiciona o repositório Adoptium (Temurin)
wget -qO - https://packages.adoptium.net/artifactory/api/gpg/key/public | sudo gpg --dearmor -o /etc/apt/keyrings/adoptium.gpg
echo "deb [signed-by=/etc/apt/keyrings/adoptium.gpg] https://packages.adoptium.net/artifactory/deb $(lsb_release -cs) main" | sudo tee /etc/apt/sources.list.d/adoptium.list
sudo apt-get update
sudo apt-get install -y temurin-21-jdk
java -version   # esperado: openjdk 21.x
```

## 2. PostgreSQL nativo

```bash
sudo apt-get install -y postgresql postgresql-contrib
sudo systemctl enable --now postgresql
```

## 3. Banco e usuário

```bash
sudo -u postgres psql -c "CREATE USER nyxcash WITH PASSWORD '<SENHA_FORTE>';"
sudo -u postgres psql -c "CREATE DATABASE nyxcash OWNER nyxcash;"
```

> As migrations Flyway (V1→V28, diretório src/main/resources/db/migration) são aplicadas
automaticamente no boot — não
> rode SQL manualmente.

## 4. Artefato (JAR executável)

Na máquina de build:

```bash
mvn -q clean package
# gera target/foxcash-backend-0.1.0-SNAPSHOT.jar
```

No VPS:

```bash
sudo mkdir -p /opt/foxcash /etc/foxcash
sudo useradd --system --home /opt/foxcash --shell /usr/sbin/nologin foxcash
# copie o JAR (scp/rsync) para /tmp e mova:
sudo install -o foxcash -g foxcash /tmp/foxcash-backend-0.1.0-SNAPSHOT.jar /opt/foxcash/foxcash.jar
```

## 5. Keystore TLS (PKCS12)

O profile `prod` ativa TLS na própria aplicação (porta 8443, HTTP/2). Para um
certificado de teste (ou importar de uma CA, ex.: Let's Encrypt):

```bash
sudo keytool -genkeypair -alias foxcash -keyalg RSA -keysize 2048 \
  -storetype PKCS12 -keystore /etc/foxcash/keystore.p12 \
  -storepass '<SENHA_TLS>' -dname "CN=api.seudominio.com.br" -validity 365
sudo chmod 600 /etc/foxcash/keystore.p12
```

## 6. Ambiente (`/etc/foxcash/foxcash.env`)

```bash
sudo tee /etc/foxcash/foxcash.env > /dev/null <<'EOF'
DB_URL=jdbc:postgresql://localhost:5432/nyxcash
DB_USERNAME=nyxcash
DB_PASSWORD=<SENHA_FORTE>
FOXCASH_CRYPTO_SECRET=<CHAVE_MAESTRA_AES_ALEA>
FOXCASH_SSL_KEYSTORE=/etc/foxcash/keystore.p12
FOXCASH_SSL_KEYSTORE_PASSWORD=<SENHA_TLS>
EOF
sudo chown root:root /etc/foxcash/foxcash.env && sudo chmod 600 /etc/foxcash/foxcash.env
```

> Sem `FOXCASH_CRYPTO_SECRET` (ou `DB_*`) o boot falha de propósito — prod não tem
> default inseguro.

## 7. systemd

```bash
sudo install -o root -g root deploy/foxcash.service /etc/systemd/system/foxcash.service
sudo systemctl daemon-reload
sudo systemctl enable --now foxcash
sudo systemctl status foxcash
```

## 8. nginx como proxy TCP (TLS termina na aplicação)

Topologia recomendada: a **aplicação** termina o TLS (porta 8443); o nginx faz
apenas passthrough de `443` para `127.0.0.1:8443`:

```nginx
# /etc/nginx/nginx.conf — bloco `stream` (fora do bloco `http`)
stream {
    server {
        listen 443;
        proxy_pass 127.0.0.1:8443;
    }
}
```

> **Alternativa (nginx termina TLS):** nginx com certificado, proxy_pass HTTP
> para `127.0.0.1:8080` e a aplicação rodando com `require-https=false` (o
> canal seguro é responsabilidade do nginx). Nesse caso use um profile/override
> próprio. A topologia 1 é a default porque o profile `prod` exige
> `require-https: true`.

## 9. Backup (cron)

```bash
sudo crontab -e
# Diário às 02:30 (UTC), retenção de 14 dias
30 2 * * * PGPASSWORD='<SENHA_FORTE>' pg_dump -U foxcash -h localhost foxcash -Fc -f /var/backups/foxcash_$(date +\%F).dump && find /var/backups -name 'foxcash_*.dump' -mtime +14 -delete
```

## 10. Verificação

```bash
curl -k https://localhost:8443/api/v1/admin/merchants     # 200 (permitAll)
curl  http://localhost:8443/api/v1/admin/merchants        # 302 → HTTPS
sudo journalctl -u foxcash -f                              # logs
```

## Atualização

```bash
sudo install -o foxcash -g foxcash /tmp/foxcash-*.jar /opt/foxcash/foxcash.jar
sudo systemctl restart foxcash
```
