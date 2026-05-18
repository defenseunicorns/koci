package com.defenseunicorns.koci.buildlogic.plugin

import com.vanniktech.maven.publish.MavenPublishBaseExtension
import java.net.URI
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.publish.PublishingExtension
import org.gradle.kotlin.dsl.apply
import org.gradle.kotlin.dsl.configure

class PublishingConventionPlugin : Plugin<Project> {
  override fun apply(target: Project) {
    with(target) {
      apply(plugin = "com.vanniktech.maven.publish")

      version = readReleasePleaseVersion()

      configure<MavenPublishBaseExtension> {
        publishToMavenCentral(automaticRelease = true)
        signAllPublications()

        pom {
          name.set(project.name)
          description.set("Kotlin implementation of the OCI Distribution client specification")
          url.set("https://github.com/defenseunicorns/koci")

          licenses {
            license {
              name.set("The Apache License, Version 2.0")
              url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
              distribution.set("repo")
            }
          }

          developers { developer { name.set("Defense Unicorns") } }

          scm {
            connection.set("scm:git:git://github.com/defenseunicorns/koci.git")
            developerConnection.set("scm:git:ssh://github.com/defenseunicorns/koci.git")
            url.set("https://github.com/defenseunicorns/koci")
          }
        }
      }

      configure<PublishingExtension> {
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
    }
  }

  private fun Project.readReleasePleaseVersion(): String {
    val manifest = rootProject.file(".release-please-manifest.json")
    val regex = Regex("\"\\.\"\\s*:\\s*\"([^\"]+)\"")
    return regex.find(manifest.readText())?.groupValues?.get(1)
      ?: error("Unable to read root version from ${manifest.path}")
  }
}
