plugins {
    id("nails-gag.java-library")
}

dependencies {
    api(project(":nails-gag-common"))
    implementation(project(":nails-gag-clients"))
    implementation(libs.spring.boot.starter.data.jpa)
    implementation(libs.spring.boot.starter.validation)
    // Обмен кода на профиль у Яндекс ID и VK ID идёт с сервера через RestClient.
    implementation(libs.spring.boot.starter.restclient)

    testImplementation(project(":nails-gag-test-support"))
}
