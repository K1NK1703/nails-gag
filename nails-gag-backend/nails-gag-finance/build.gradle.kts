plugins {
    id("nails-gag.java-library")
}

dependencies {
    api(project(":nails-gag-common"))
    // Денежные факты визитов и предоплат — только через BookingFinance (отчёты).
    implementation(project(":nails-gag-booking"))
    implementation(libs.spring.boot.starter.data.jpa)
    // Снимки записей в журнале изменений — jsonb через JsonMapper приложения.
    implementation(libs.spring.boot.starter.jackson)

    testImplementation(project(":nails-gag-test-support"))
}
