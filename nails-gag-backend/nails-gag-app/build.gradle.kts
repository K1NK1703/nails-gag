plugins {
    id("nails-gag.java-library")
    alias(libs.plugins.spring.boot)
}

dependencies {
    implementation(project(":nails-gag-common"))

    implementation(libs.spring.boot.starter.webmvc)
    implementation(libs.spring.boot.starter.data.jpa)
    implementation(libs.spring.boot.starter.jdbc)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.spring.boot.starter.liquibase)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.modulith.starter.core)
    runtimeOnly(libs.postgresql)

    // developmentOnly не наследует BOM из convention-плагина (он на api), поэтому BOM здесь явно.
    developmentOnly(platform(libs.spring.boot.bom))
    developmentOnly(libs.spring.boot.docker.compose)

    // app не использует test-support: у него свой класс приложения в том же пакете gag.nails,
    // и два @SpringBootApplication рядом ломали бы поиск конфигурации в тестах.
    testImplementation(libs.spring.boot.testcontainers)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.spring.modulith.starter.test)
    testImplementation(libs.spring.modulith.docs)
    testImplementation(libs.archunit.junit5)
}

springBoot {
    mainClass = "gag.nails.NailsGagApplication"
}

tasks.bootJar {
    archiveFileName = "app.jar"
}

// bootRun работает из корня проекта, иначе профиль local не найдёт compose.yaml.
tasks.bootRun {
    workingDir = rootDir
}
