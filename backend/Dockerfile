FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# 빌드 아티팩트 복사 (mvn package 후 실행)
COPY target/server-*.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", \
  "-XX:+UseContainerSupport", \
  "-XX:MaxRAMPercentage=75.0", \
  "-jar", "app.jar"]
