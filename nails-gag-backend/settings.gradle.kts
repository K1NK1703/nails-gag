pluginManagement {
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

rootProject.name = "nails-gag-backend"

include("nails-gag-common")
include("nails-gag-test-support")
include("nails-gag-files")
include("nails-gag-catalog")
include("nails-gag-clients")
include("nails-gag-schedule")
include("nails-gag-booking")
include("nails-gag-portfolio")
include("nails-gag-finance")
include("nails-gag-identity")
include("nails-gag-api")
include("nails-gag-app")
