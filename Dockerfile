# ---------- Bước 1: build file WAR bằng Maven ----------
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q package -DskipTests

# ---------- Bước 2: chạy trên Tomcat 10.1 ----------
FROM tomcat:10.1-jdk17-temurin
ENV TZ=Asia/Ho_Chi_Minh \
    NODE_ENV=production \
    JAVA_OPTS="-Xmx320m -XX:+UseSerialGC -Dfile.encoding=UTF-8"
RUN rm -rf /usr/local/tomcat/webapps/*
COPY --from=build /app/target/ROOT.war /usr/local/tomcat/webapps/ROOT.war
EXPOSE 8080
# Render đặt cổng qua biến PORT → sửa cổng của Tomcat trước khi chạy
CMD ["sh", "-c", "sed -i \"s/port=\\\"8080\\\"/port=\\\"${PORT:-8080}\\\"/\" /usr/local/tomcat/conf/server.xml && exec catalina.sh run"]
