# ---- Build stage: Maven + JDK 21 ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app

# Cache de dependências (apenas pom.xml primeiro)
COPY pom.xml .
RUN mvn -B -DskipTests dependency:go-offline || true

COPY src ./src
RUN mvn -B -DskipTests package

# ---- Runtime stage: JDK 21 JRE ----
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/target/foxcash-backend-0.1.0-SNAPSHOT.jar app.jar

# A porta real é injetada pelo Railway ($PORT) — o perfil prod,railway usa
# server.port=${PORT}. EXPOSE é apenas documental.
EXPOSE 8080
# Spec 60 (2026-08-08): teto de heap explícito via JAVA_OPTS (env Railway,
# ex.: -Xmx320m — NÃO usar -XX:MaxMetaspaceSize=96m: causa OutOfMemoryError:
# Metaspace no boot, verificado 2026-08-08). $JAVA_OPTS permite ajustar sem
# rebuild. Default seguro: MaxRAMPercentage=75.
ENTRYPOINT ["sh", "-c", "java ${JAVA_OPTS:--XX:MaxRAMPercentage=75} -jar app.jar"]
