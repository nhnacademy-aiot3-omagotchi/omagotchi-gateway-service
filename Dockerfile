FROM eclipse-temurin:21-jdk-alpine AS builder

WORKDIR /workspace

RUN apk add --no-cache bash curl

COPY . .

RUN chmod +x mvnw \
    && ./mvnw -B -DskipTests package


FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

RUN apk add --no-cache curl

COPY --from=builder /workspace/target/*.jar app.jar

ENTRYPOINT ["java", "-jar", "/app/app.jar"]