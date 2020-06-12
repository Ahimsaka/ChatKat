FROM openjdk:14-jdk
ARG BOT_TOKEN
ARG DEBUG
ENV BOT_TOKEN=$BOT_TOKEN
ENV DEBUG=$DEBUG
COPY . .
# /app
# WORKDIR /app
ENTRYPOINT ["./gradlew", "run","--no-daemon"]

