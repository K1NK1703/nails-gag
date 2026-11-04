plugins {
    id("nails-gag.java-library")
}

dependencies {
    api(project(":nails-gag-common"))
    implementation(libs.spring.boot.starter.data.jpa)

    testImplementation(project(":nails-gag-test-support"))
}
