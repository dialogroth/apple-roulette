FROM gradle:8.11-jdk21 AS build

WORKDIR /app

# キャッシュレイヤーの最適化
COPY build.gradle.kts settings.gradle.kts gradle.properties ./
COPY gradle ./gradle
COPY gradlew* ./

# ソースコードコピー
COPY src ./src
COPY files ./files

# ビルド実行
RUN ./gradlew build --no-daemon

FROM eclipse-temurin:21

WORKDIR /app

# 明示的にJARファイルをコピー
COPY --from=build /app/build/libs/apple-roulette-all.jar app.jar

# ヘルスチェック用のポート確認
EXPOSE 8080

# JVM オプション設定（メモリ最適化）
ENV JAVA_OPTS="-Xmx512m -Xms256m"

CMD ["java", "-jar", "app.jar"]