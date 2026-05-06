package com.defenseunicorns.koci.buildlogic.plugin

import com.defenseunicorns.koci.buildlogic.helper.libs
import com.diffplug.gradle.spotless.SpotlessExtension
import dev.detekt.gradle.Detekt
import dev.detekt.gradle.extensions.DetektExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.apply
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.withType

class LintConventionPlugin : Plugin<Project> {
  override fun apply(target: Project) {
    with(target) {
      configureDetekt()
      configureSpotless()
    }
  }

  private fun Project.configureDetekt() {
    apply(plugin = "dev.detekt")

    configure<DetektExtension> {
      buildUponDefaultConfig.set(true)
      config.setFrom("$rootDir/linting/detekt-config.yml")
      baseline.set(file("$rootDir/linting/detekt-baseline.xml"))
    }

    tasks.withType<Detekt> {
      reports {
        html.required.set(true)
        sarif.required.set(true)
      }
    }

    dependencies.add("detektPlugins", libs.findLibrary("detekt-library-rules").get())
  }

  private fun Project.configureSpotless() {
    apply(plugin = "com.diffplug.spotless")

    configure<SpotlessExtension> {
      val ktFiles = "**/*.kt"
      val ktsFiles = "**/*.kts"

      kotlin {
        target(ktFiles, ktsFiles)

        trimTrailingWhitespace()
        leadingSpacesToTabs(2)
        endWithNewline()

        // Google style is blockIndent = 2, continuationIndent = 2, manageTrailingCommas = true
        // renovate: datasource=github-tags depName=facebook/ktfmt
        ktfmt("0.58").googleStyle().configure {
          it.setMaxWidth(100)
          it.setRemoveUnusedImports(true)
        }
      }

      // Excludes kts files from license check
      format("kt-license") {
        target(ktFiles)
        licenseHeaderFile("$rootDir/linting/LICENSE_TEMPLATE", "^((package|import)\\b)").apply {
          updateYearWithLatest(true)
        }
      }
    }
  }
}
