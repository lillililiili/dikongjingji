FROM eclipse-temurin:21-jdk-jammy AS build

WORKDIR /workspace

COPY .mvn .mvn
COPY mvnw pom.xml ./
COPY backend/pom.xml backend/pom.xml
COPY backend/bootstrap/pom.xml backend/bootstrap/pom.xml
COPY backend/modules/identity/pom.xml backend/modules/identity/pom.xml
COPY backend/modules/inspection/pom.xml backend/modules/inspection/pom.xml

COPY backend backend

RUN --mount=type=cache,target=/root/.m2 \
    sed -i 's/\r$//' mvnw \
    && chmod +x mvnw \
    && ./mvnw -B -pl :bootstrap -am package -DskipTests

FROM eclipse-temurin:21-jre-jammy

WORKDIR /app

RUN groupadd --system spring \
    && useradd --system --gid spring --home-dir /app --shell /usr/sbin/nologin spring

COPY --from=build /workspace/backend/bootstrap/target/bootstrap-*.jar /app/app.jar

USER spring

EXPOSE 8080

HEALTHCHECK --interval=15s --timeout=5s --start-period=45s --retries=6 CMD bash -ec 'exec 3<>/dev/tcp/127.0.0.1/8080; printf "GET /actuator/health HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n" >&3; grep -q "\"status\":\"UP\"" <&3'

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
