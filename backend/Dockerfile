FROM maven:3.9.8-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
# Unit tests run outside the image build: see README
RUN mvn -B -q clean package -DskipTests

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/target/mromirror.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
