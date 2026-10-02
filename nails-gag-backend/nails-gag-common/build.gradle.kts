plugins {
    id("nails-gag.java-library")
}

dependencies {
    api(libs.spring.boot.starter)
    api(libs.spring.boot.starter.validation)
    api(libs.spring.modulith.starter.core)

    // Для ConstraintViolations: имя нарушенного ограничения достаёт Hibernate. Не api: модули
    // подключают data-jpa сами, а common не должен навязывать JPA тем, кому он не нужен.
    implementation(libs.spring.boot.starter.data.jpa)
}
