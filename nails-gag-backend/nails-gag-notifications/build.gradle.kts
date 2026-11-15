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

    testImplementation(project(":nails-gag-test-support"))
    // Тест событий пропускает их через EventSerializer реестра и правит JSON публикаций тем же JsonMapper.
    testImplementation(libs.spring.modulith.events.core)
    testImplementation(libs.jackson.databind)
}
