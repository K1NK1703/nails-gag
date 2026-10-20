plugins {
    id("nails-gag.java-library")
}

dependencies {
    api(project(":nails-gag-common"))
    implementation(libs.spring.boot.starter.data.jpa)
    // Картинки читает ImageIO: JPEG — плагином TwelveMonkeys (CMYK, ICC-профили), WebP — только им; нативного кода нет.
    implementation(libs.twelvemonkeys.imageio.jpeg)
    implementation(libs.twelvemonkeys.imageio.webp)
    implementation(libs.metadata.extractor)

    testImplementation(project(":nails-gag-test-support"))
}
