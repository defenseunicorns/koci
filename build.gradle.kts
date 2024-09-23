import kotlinx.kover.gradle.plugin.dsl.AggregationType
import kotlinx.kover.gradle.plugin.dsl.CoverageUnit
import kotlinx.kover.gradle.plugin.dsl.GroupingEntityType
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import java.net.URI

plugins {
    alias(libs.plugins.jetbrains.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    id("org.jetbrains.kotlinx.kover") version "0.8.3"

    id("maven-publish")
}

group = "com.defenseunicorns"
version = "1.0-SNAPSHOT"

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
            name = "OSSRH"
            url = URI("https://oss.sonatype.org/service/local/staging/deploy/maven2/")
            credentials {
                username = System.getenv("MAVEN_USERNAME")
                password = System.getenv("MAVEN_PASSWORD")
            }
        }

        maven {
            name = "GitHubPackages"
            url = URI("https://maven.pkg.github.com/DefenseUnicorns/koci")
            credentials {
                username = System.getenv("GITHUB_ACTOR")
                password = System.getenv("GITHUB_TOKEN")
            }
        }
    }
}