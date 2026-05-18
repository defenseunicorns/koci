import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins { `kotlin-dsl` }

group = "com.defenseunicorns.koci.buildlogic"

java {
  sourceCompatibility = JavaVersion.VERSION_21
  targetCompatibility = JavaVersion.VERSION_21
}

kotlin { compilerOptions { jvmTarget = JvmTarget.JVM_21 } }

dependencies {
  compileOnly(libs.detekt.gradle.plugin)
  compileOnly(libs.spotless.gradle.plugin)
  compileOnly(libs.maven.publish.gradle.plugin)
}

tasks {
  validatePlugins {
    enableStricterValidation = true
    failOnWarning = true
  }
}

gradlePlugin {
  plugins {
    register("lint") {
      id = "com.defenseunicorns.koci.lint"
      implementationClass = "com.defenseunicorns.koci.buildlogic.plugin.LintConventionPlugin"
    }
    register("publishing") {
      id = "com.defenseunicorns.koci.publishing"
      implementationClass = "com.defenseunicorns.koci.buildlogic.plugin.PublishingConventionPlugin"
    }
  }
}
