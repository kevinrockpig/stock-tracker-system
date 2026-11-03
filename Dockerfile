# 網站映像：Vue 前端 build 後放進 Java 的 static/，由 Spring Boot 一起提供（網頁與 API 同一個網址）
#   建置：docker build -t stock-tracker .（在 repo 根目錄執行）
#   執行：需要的環境變數見根目錄 README「部署」；資料庫連線資訊只由環境變數提供，不會放進映像
# Python 每日更新不在這個映像內（由 GitHub Actions 或本機排程執行）

# ---- 1. 前端 ----
FROM node:22-alpine AS frontend
WORKDIR /app
COPY stock-frontend-vue/stock-tracker/package.json stock-frontend-vue/stock-tracker/package-lock.json ./
RUN npm ci
COPY stock-frontend-vue/stock-tracker/ ./
# 未設定 VITE_API_BASE_URL：API 使用相對路徑（與網頁同一個網址）
RUN npm run build

# ---- 2. Java ----
FROM eclipse-temurin:17-jdk AS backend
WORKDIR /app
COPY stock-backend-java/stock-api-java/mvnw stock-backend-java/stock-api-java/pom.xml ./
COPY stock-backend-java/stock-api-java/.mvn .mvn
# 先下載相依套件（pom.xml 沒變時沿用快取，只改程式碼時重新建置較快）
RUN ./mvnw -q -B dependency:go-offline
COPY stock-backend-java/stock-api-java/src/main src/main
COPY --from=frontend /app/dist src/main/resources/static
# 測試需要資料庫，在本機執行（見 stock-backend-java/stock-api-java/README.md），建置映像時略過
RUN ./mvnw -q -B -DskipTests package && cp target/*-SNAPSHOT.jar app.jar

# ---- 3. 執行 ----
FROM eclipse-temurin:17-jre
# 時區與開發環境（台灣）一致：每日清除過期 session 的排程時間等依此計算
ENV TZ=Asia/Taipei
# 記憶體：heap 上限 256 MB，實測整個程序約 300～350 MB，可在 512 MB 的主機執行；
# 小 heap 用 Serial GC 額外記憶體最少（平台給的 CPU 較多時 JVM 會改選 G1，這裡固定）
ENV JAVA_TOOL_OPTIONS="-Xmx256m -XX:+UseSerialGC"
# 平台（Render、Railway 等）以 PORT 環境變數指定連接埠；沒有時用 8080
ENV PORT=8080
RUN useradd --system --uid 10001 --no-create-home app
WORKDIR /app
COPY --from=backend /app/app.jar app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
