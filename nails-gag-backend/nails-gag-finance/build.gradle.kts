plugins {
    id("nails-gag.java-library")
}

dependencies {
    api(project(":nails-gag-common"))
    // Зависимость на booking (BookingFinance — денежные факты визитов и предоплат) добавит задача отчётов.
    implementation(libs.spring.boot.starter.data.jpa)
    // Снимки записей в журнале изменений — jsonb через свой JsonMapper модуля.
    implementation(libs.jackson.databind)

    testImplementation(project(":nails-gag-test-support"))
}
