# One Dockerfile for all four services: docker build --build-arg SERVICE=order .
FROM maven:3.9-eclipse-temurin-21 AS build
ARG SERVICE
WORKDIR /src
COPY pom.xml .
COPY services ./services
RUN mvn -q -pl services/${SERVICE} -am -DskipTests package

FROM eclipse-temurin:21-jre
ARG SERVICE
COPY --from=build /src/services/${SERVICE}/target/${SERVICE}-0.1.0.jar /app/app.jar
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
