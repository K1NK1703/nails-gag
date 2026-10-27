# Образ собирается из готового app.jar (./gradlew :nails-gag-app:bootJar), сборка Gradle в образе не нужна.
FROM eclipse-temurin:25-jre AS extract
WORKDIR /build
COPY nails-gag-app/build/libs/app.jar app.jar
RUN java -Djarmode=tools -jar app.jar extract --layers --destination extracted

FROM eclipse-temurin:25-jre
WORKDIR /app
RUN groupadd --system app && useradd --system --gid app --uid 1001 app
# Каталог хранилища фото (nails-gag.files.root) — владелец app: под ним приложение и работает. При запуске сюда
# монтируется том; новый том Docker копирует владельца и права каталога образа.
RUN mkdir -p /var/lib/nails-gag/files && chown app:app /var/lib/nails-gag/files
# Слои от редко меняющихся к часто меняющимся: зависимости кэшируются между сборками.
COPY --from=extract /build/extracted/dependencies/ ./
COPY --from=extract /build/extracted/spring-boot-loader/ ./
COPY --from=extract /build/extracted/snapshot-dependencies/ ./
COPY --from=extract /build/extracted/application/ ./
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
