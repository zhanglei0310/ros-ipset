FROM openjdk:8-jre-slim

RUN mkdir /app
COPY . /app

WORKDIR /app
CMD ["java", "-cp", "jrodns.jar:libs/*", "tech.stacktrace.jrodns.Application"]