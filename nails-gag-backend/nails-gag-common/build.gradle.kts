plugins {
    id("nails-gag.java-library")
}

dependencies {
    api(libs.spring.boot.starter)
    api(libs.spring.boot.starter.validation)
    api(libs.spring.modulith.starter.core)

    // Только для компиляции ConstraintViolations: имя нарушенного ограничения достаёт Hibernate.
    // Модули подключают data-jpa сами, поэтому common его не распространяет.
    compileOnly(libs.spring.boot.starter.data.jpa)
    testImplementation(libs.spring.boot.starter.data.jpa)
}
