plugins {
    id("nails-gag.java-library")
}

dependencies {
    api(project(":nails-gag-common"))
    // api: вид услуги каталога (ServiceKind) входит в публичный отчёт по услугам.
    api(project(":nails-gag-catalog"))
    // Денежные факты визитов и удержанных предоплат (BookingFinance) — источник дохода от записей в отчётах.
    implementation(project(":nails-gag-booking"))
    implementation(libs.spring.boot.starter.data.jpa)
    // Снимки записей в журнале изменений — jsonb через свой JsonMapper модуля.
    implementation(libs.jackson.databind)

    testImplementation(project(":nails-gag-test-support"))
    // Тесты отчётов заводят клиентов и слоты визитов через публичные API этих модулей.
    testImplementation(project(":nails-gag-clients"))
    testImplementation(project(":nails-gag-schedule"))
}
