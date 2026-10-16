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

    testImplementation(project(":nails-gag-test-support"))
    testImplementation(libs.spring.boot.resttestclient)
}
