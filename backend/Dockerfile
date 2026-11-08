FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY .mvn .mvn
COPY mvnw pom.xml ./
# mvnw из Windows-клона приходит с CRLF — без этого "/bin/sh^M: not found"
RUN sed -i 's/\r$//' mvnw && chmod +x mvnw && ./mvnw -q -B dependency:go-offline
COPY src src
RUN ./mvnw -q -B -DskipTests package && cp target/*.jar app.jar

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN useradd --system --no-create-home cqa
COPY --from=build /app/app.jar app.jar
USER cqa
EXPOSE 8080
# JAVA_OPTS задаётся в docker-compose (по умолчанию -XX:MaxRAMPercentage=75)
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
