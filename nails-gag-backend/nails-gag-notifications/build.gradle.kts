plugins {
    id("nails-gag.java-library")
}

dependencies {
    api(project(":nails-gag-common"))
    // События записи и BookingQueries: сообщения о записях и расчёт напоминаний.
    implementation(project(":nails-gag-booking"))
    // События карточек и ClientQueries: почта карточки и её аккаунт.
    implementation(project(":nails-gag-clients"))
    // AccountDeleted и StaffDirectory: удаление аккаунта и получатели мастерских сообщений.
    implementation(project(":nails-gag-identity"))
    implementation(libs.spring.boot.starter.data.jpa)
    // Реестр публикаций Spring Modulith в PostgreSQL: событие, не обработанное до падения, повторяется после рестарта.
    implementation(libs.spring.modulith.starter.jdbc)
    implementation(libs.spring.boot.starter.mail)
    // Коды ответов SMTP из исключений Angus Mail: постоянный отказ 5xx отличается от временного 4xx.
    implementation(libs.angus.mail)
    // RestClient к Bot API Telegram (sendMessage, getUpdates) с разбором JSON.
    implementation(libs.spring.boot.starter.restclient)

    testImplementation(project(":nails-gag-test-support"))
    // Тесты очереди заводят слоты для настоящих записей через публичный API расписания.
    testImplementation(project(":nails-gag-schedule"))
    // Тест событий пропускает их через EventSerializer реестра и правит JSON публикаций тем же JsonMapper.
    testImplementation(libs.spring.modulith.events.core)
    testImplementation(libs.jackson.databind)
    // Почтовый сервер в памяти для тестов писем. JUnit 4 (для его правила) не нужен; Jakarta Mail — та же, что у
    // приложения (angus-mail), а не её сборка jakarta.mail, тянущая те же классы второй раз.
    testImplementation(libs.greenmail) {
        exclude(group = "junit", module = "junit")
        exclude(group = "org.eclipse.angus", module = "jakarta.mail")
    }
}
