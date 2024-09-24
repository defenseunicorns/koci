import io.gitlab.arturbosch.detekt.Detekt
import kotlinx.kover.gradle.plugin.dsl.AggregationType
import kotlinx.kover.gradle.plugin.dsl.CoverageUnit
import kotlinx.kover.gradle.plugin.dsl.GroupingEntityType
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import java.net.URI

plugins {
    alias(libs.plugins.jetbrains.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kover)
    alias(libs.plugins.detekt)

    id("maven-publish")
}

group = "com.defenseunicorns"

repositories {
    mavenCentral()
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)

    detektPlugins(libs.detekt.formatting)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()

    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = TestExceptionFormat.FULL
        showExceptions = true
        showCauses = true
        showStackTraces = true
    }
}

detekt {
    toolVersion = libs.versions.detekt.get()
    config.setFrom("$rootDir/detekt.yml")
}

tasks.withType<Detekt> {
    reports {
        html.required.set(true)
        xml.required.set(false)
        txt.required.set(false)
    }
}

kover {
    reports {
        total {
            log {
                groupBy = GroupingEntityType.APPLICATION
                aggregationForGroup = AggregationType.COVERED_PERCENTAGE
                format = "<entity> line coverage: <value>%"
                coverageUnits = CoverageUnit.LINE
            }
        }
    }
}

publishing {
    repositories {
        maven {
            name = "GitHubPackages"
            url = URI("https://maven.pkg.github.com/defenseunicorns/koci")
            credentials {
                username = System.getenv("GITHUB_ACTOR")
                password = System.getenv("GITHUB_TOKEN")
            }
        }
    }
}