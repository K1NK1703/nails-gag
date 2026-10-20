import org.gradle.api.tasks.testing.logging.TestExceptionFormat

plugins {
    `java-library`
    id("com.diffplug.spotless")
}

val libs = the<VersionCatalogsExtension>().named("libs")

group = "gag.nails"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

dependencies {
    // Версии всех Spring-зависимостей — из BOM, в подпроектах версии не пишутся.
    api(platform(libs.findLibrary("spring-boot-bom").get()))
    api(platform(libs.findLibrary("spring-modulith-bom").get()))

    testImplementation(libs.findLibrary("spring-boot-starter-test").get())
    testRuntimeOnly(libs.findLibrary("junit-platform-launcher").get())
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = 25
    // -parameters: имена параметров для Spring; предупреждения — это ошибки.
    options.compilerArgs.addAll(listOf("-parameters", "-Xlint:all,-processing,-serial", "-Werror"))
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    // Хранилище файлов тестов (nails-gag.files.root) — свой каталог задачи в build/ модуля, очищается перед каждым
    // прогоном: мусор не копится между прогонами, параллельные модули не делят файлы. Путь относительный (рабочий
    // каталог тестов — каталог модуля), чтобы не мешать кэшу сборки.
    val filesRoot = layout.buildDirectory.dir("test-files/$name")
    systemProperty("nails-gag.files.root", "build/test-files/$name")
    doFirst {
        filesRoot.get().asFile.deleteRecursively()
    }
    testLogging {
        events("failed", "skipped")
        exceptionFormat = TestExceptionFormat.FULL
        showStandardStreams = false
    }
}

spotless {
    java {
        palantirJavaFormat(libs.findVersion("palantirJavaFormat").get().requiredVersion)
        removeUnusedImports()
        endWithNewline()
    }
    format("misc") {
        target("*.gradle.kts", "src/**/*.yaml", "src/**/*.sql", "src/**/*.properties")
        trimTrailingWhitespace()
        endWithNewline()
    }
}
