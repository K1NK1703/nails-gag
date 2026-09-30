plugins {
    id("nails-gag.java-library")
}

// Тестовая обвязка подключается доменными модулями как testImplementation, поэтому её
// зависимости — обычные api/implementation, а не test*.
dependencies {
    api(project(":nails-gag-common"))
    api(libs.spring.boot.starter.data.jpa)
    api(libs.spring.boot.starter.liquibase)
    api(libs.spring.boot.starter.test)
    api(libs.spring.boot.testcontainers)
    api(libs.testcontainers.postgresql)
    api(libs.spring.modulith.starter.core)
    api(libs.spring.modulith.starter.test)
    runtimeOnly(libs.postgresql)
}
