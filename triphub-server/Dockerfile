FROM eclipse-temurin:17-jre AS runtime

WORKDIR /app

# 默认从 Maven 打包后的 jar 路径复制
ARG JAR_FILE=target/triphub-server-1.0-SNAPSHOT.jar
COPY ${JAR_FILE} app.jar

# 默认使用 prod 配置，并内置 JDK17 + MyBatis-Plus 所需的 --add-opens
ENV SPRING_PROFILES_ACTIVE=prod \
    SERVER_PORT=8090 \
    JAVA_OPTS="--add-opens java.base/java.lang.invoke=ALL-UNNAMED"

EXPOSE 8090

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]


