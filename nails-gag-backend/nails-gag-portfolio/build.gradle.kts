plugins {
    id("nails-gag.java-library")
}

dependencies {
    api(project(":nails-gag-common"))
    implementation(project(":nails-gag-files"))
    implementation(project(":nails-gag-clients"))
    implementation(project(":nails-gag-booking"))
    implementation(libs.spring.boot.starter.data.jpa)

    testImplementation(project(":nails-gag-test-support"))
    // Тесты заводят слоты для записей, к которым относятся работы клиентов.
    testImplementation(project(":nails-gag-schedule"))
}
