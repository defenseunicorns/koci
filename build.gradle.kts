import com.vanniktech.maven.publish.SonatypeHost
import io.gitlab.arturbosch.detekt.Detekt
import kotlinx.kover.gradle.plugin.dsl.AggregationType
import kotlinx.kover.gradle.plugin.dsl.CoverageUnit
import kotlinx.kover.gradle.plugin.dsl.GroupingEntityType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import java.net.URI

plugins {
    alias(libs.plugins.jetbrains.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kover)
    alias(libs.plugins.detekt)

    alias(libs.plugins.maven.publish)
}

buildscript {
    dependencies {
        classpath(libs.kotlinx.serialization.json)
    }
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

val releasePleaseManifest = file(".release-please-manifest.json")

version = Json.decodeFromString<JsonElement>(releasePleaseManifest.readText()).jsonObject["."]?.jsonPrimitive?.content!!

mavenPublishing {
    publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL, automaticRelease = true)
    signAllPublications()

    pom {
        name = project.name
        description = "Kotlin implementation of the OCI Distribution client specification"
        url = "https://github.com/defenseunicorns/koci"

        licenses {
            license {
                name = "The Apache License, Version 2.0"
                url = "http://www.apache.org/licenses/LICENSE-2.0.txt"
                distribution = "repo"
            }
        }

        developers {
            developer {
                name = "Defense Unicorns"
            }
        }

        scm {
            connection = "scm:git:git://github.com/defenseunicorns/koci.git"
            developerConnection = "scm:git:ssh://github.com/defenseunicorns/koci.git"
            url = "https://github.com/defenseunicorns/koci"
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

tasks.register<Test>("allTests") {
    systemProperty("TESTS_WITH_EXTERNAL_SERVICES", "true")
    useJUnitPlatform()
}
