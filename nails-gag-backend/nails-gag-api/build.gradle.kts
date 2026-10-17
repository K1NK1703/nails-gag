plugins {
    id("nails-gag.java-library")
}

dependencies {
    implementation(project(":nails-gag-common"))
    implementation(project(":nails-gag-catalog"))
    implementation(project(":nails-gag-clients"))
    implementation(project(":nails-gag-schedule"))
    implementation(project(":nails-gag-booking"))
    implementation(project(":nails-gag-identity"))
    implementation(libs.spring.boot.starter.webmvc)
    implementation(libs.spring.boot.starter.validation)
    // Взаимная блокировка в БД приходит как PessimisticLockingFailureException и отвечает 503 common.busy.
    implementation(libs.spring.tx)
    // Лимиты частоты: корзины Bucket4j в памяти, устаревание ключей — Caffeine.
    implementation(libs.bucket4j.core)
    implementation(libs.caffeine)
    // Настройка схемы OpenAPI (OpenApiConfiguration) компилируется против springdoc, а работает, только когда app
    // подключает его при bootRun (developmentOnly); в рабочем classpath springdoc нет.
    compileOnly(libs.springdoc.common)

    testImplementation(project(":nails-gag-test-support"))
    testImplementation(libs.spring.boot.resttestclient)
    // OpenApiSchemaTest проверяет схему, которую при bootRun отдаёт springdoc; в рабочий classpath он не попадает.
    testImplementation(libs.springdoc.webmvc.ui)
}
