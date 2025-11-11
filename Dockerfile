FROM maven:3-eclipse-temurin-17 AS build
COPY . .
RUN mvn clean package -DskipTests
FROM eclipse-temurin:17-alpine
COPY --from=build /target/EvilHamster-0.0.1-SNAPSHOT.jar EvilHamster-0.0.1-SNAPSHOT.jar
ENTRYPOINT ["java","-Dspring.profiles.active=render","-jar","EvilHamsterFutures-0.0.1-SNAPSHOT.jar"]