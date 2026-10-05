plugins {
    id("nails-gag.java-library")
}

dependencies {
    api(project(":nails-gag-common"))
    // api: типы каталога (ServiceKind) входят в публичные представления записи.
    api(project(":nails-gag-catalog"))
    implementation(project(":nails-gag-clients"))
    implementation(project(":nails-gag-schedule"))
    implementation(libs.spring.boot.starter.data.jpa)

    testImplementation(project(":nails-gag-test-support"))
}
