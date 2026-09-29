FROM maven:3.9-eclipse-temurin-21 AS builder
WORKDIR /app

# Capa de dependencias: se recompila solo si cambia el pom
COPY pom.xml .
RUN mvn -B dependency:go-offline

COPY src ./src
RUN mvn -B clean package -DskipTests

# Misma versión de Alpine que la imagen de Go.
FROM eclipse-temurin:21.0.12.1_1-jre-alpine-3.24
WORKDIR /app
COPY --from=builder /app/target/*.jar app.jar
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "app.jar"]
