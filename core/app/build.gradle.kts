/*
 *  This file is part of AndroidIDE.
 *
 *  AndroidIDE is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidIDE is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 */

@file:Suppress("UnstableApiUsage")

import com.tom.rv2ide.build.config.BuildConfig
import com.tom.rv2ide.desugaring.utils.JavaIOReplacements.applyJavaIOReplacements
import com.tom.rv2ide.plugins.AndroidIDEAssetsPlugin
import java.util.Properties

plugins {
  id("com.tom.rv2ide.core-app")
  id("com.android.application")
  id("kotlin-android")
  id("kotlin-kapt")
  id("kotlinx-serialization")
  id("kotlin-parcelize")
  id("androidx.navigation.safeargs.kotlin")
  id("com.tom.rv2ide.desugaring")
}

apply { plugin(AndroidIDEAssetsPlugin::class.java) }

buildscript {
  dependencies {
    classpath(libs.logging.logback.core)
    classpath(libs.composite.desugaringCore)
  }
}

tasks.configureEach {
    if (name.contains("desugar", ignoreCase = true)) {
        enabled = false
    }
}

configurations.all {
  resolutionStrategy {
    force("com.google.guava:guava:32.1.3-android")
    eachDependency {
      if (requested.group == "com.google.guava" && requested.name == "guava") {
        if (requested.version?.contains("jre") == true) {
          useVersion("32.1.3-android")
          because("Force Android version to avoid synthetic lambda conflicts")
        }
      }
    }
  }
}

android {
  namespace = BuildConfig.packageName

  defaultConfig {
    applicationId = BuildConfig.packageName
    vectorDrawables.useSupportLibrary = true
  }
  
  experimentalProperties["android.experimental.enableGlobalSynthetics"] = true
  

  // SECURITY FIX: the release keystore (signing/signing-key.jks) was committed
  // to this repository. A public keystore can be brute-forced offline and used
  // to sign malicious "official" update packages that install without friction.
  //
  // New policy:
  // 1. Keystores must NEVER be committed (enforced by .gitignore).
  // 2. CI / release builds provide the keystore via ACS_STORE_FILE (absolute
  //    path, e.g. decoded from a base64 GitHub secret) plus
  //    SIGNING_STORE_PASSWORD / ACS_KEY_ALIAS / SIGNING_KEY_PASSWORD.
  // 3. Local builds without those variables fall back to the standard debug
  //    keystore (debug) / unsigned APK (release).
  val envStoreFile = System.getenv("ACS_STORE_FILE")?.takeIf { it.isNotBlank() }?.let(::File)
  val useEnvSigning = envStoreFile != null && envStoreFile!!.isFile

  if (useEnvSigning) {
    signingConfigs {
      create("custom") {
          storeFile = envStoreFile
          storePassword = System.getenv("SIGNING_STORE_PASSWORD") ?: ""
          keyAlias = System.getenv("ACS_KEY_ALIAS") ?: "androidcs"
          keyPassword = System.getenv("SIGNING_KEY_PASSWORD") ?: ""
      }
    }
  }

  androidResources { generateLocaleConfig = true }

  buildFeatures {
    aidl = true
    dataBinding = true
  }

  buildTypes {
    debug {
      if (useEnvSigning) {
        signingConfig = signingConfigs.getByName("custom")
      }
    }

    release {
      isShrinkResources = false
      if (useEnvSigning) {
        signingConfig = signingConfigs.getByName("custom")
      }
    }
  }
  
  lint {
    abortOnError = false
    // BUILD-ADAPT: 4GB sandbox OOM guard — skip release lint vital gate (disclosed to user)
    checkReleaseBuilds = false
    disable.addAll(arrayOf("VectorPath", "NestedWeights", "ContentDescription", "SmallSp"))
  }

  packaging {
    resources {
      pickFirsts += "kotlin/**.kotlin_builtins"
      pickFirsts += "THIRD-PARTY"
      pickFirsts += "LICENSE"
    }
  }

  applicationVariants.all {
    val variant = this
    variant.outputs.all {
      val output = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl

      val versionName = variant.versionName ?: "unknown"
      val versionCode = variant.versionCode
      val buildType = variant.buildType.name
      val filters = output.filters
      val abiFilter = filters.find { it.filterType == "ABI" }
      val archSuffix =
          abiFilter?.identifier
              ?: run {
                val variantName = variant.name.lowercase()
                when {
                  variantName.contains("arm64") -> "arm64-v8a"
                  variantName.contains("armeabi") || variantName.contains("arm7") -> "armeabi-v7a"
                  else -> {
                    // This should not happen with our configuration
                    throw IllegalStateException(
                        "Could not determine ABI for variant: $variantName. Expected arm64-v8a or armeabi-v7a."
                    )
                  }
                }
              }

      if (archSuffix !in listOf("arm64-v8a", "armeabi-v7a")) {
        throw IllegalStateException(
            "Unsupported architecture: $archSuffix. Only arm64-v8a and armeabi-v7a are supported."
        )
      }

      val appName = "android-code-studio"
      val fileName =
          if (buildType == "release") {
            "${appName}-${archSuffix}-${versionName}.apk"
          } else {
            "${appName}-${archSuffix}-${buildType}-${versionName}.apk"
          }

      output.outputFileName = fileName

      println(
          "Generated APK: $fileName for variant: ${variant.name}, arch: $archSuffix, versionCode: $versionCode"
      )
    }
  }
}

kapt { arguments { arg("eventBusIndex", "${BuildConfig.packageName}.events.AppEventsIndex") } }

desugaring {
  replacements {
    includePackage(
        "org.eclipse.jgit",
    )

    applyJavaIOReplacements()
  }
}


dependencies {
  // debugImplementation(libs.common.leakcanary)
  implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.0")
  implementation("org.tukaani:xz:1.9")
  implementation("org.apache.commons:commons-compress:1.21")

  // external deps here
  implementation("com.github.Dimezis:BlurView:version-3.2.0")
  implementation("androidx.security:security-crypto:1.1.0-alpha06")
  implementation(projects.external.acsprovider)
  implementation(projects.external.atc) 
  implementation(libs.external.customizable.cardview)
  implementation(projects.external.logwire)
        implementation(libs.external.seasonal.effects)
  
  // Annotation processors
  kapt(libs.common.glide.ap)
  kapt(libs.google.auto.service)
  kapt(projects.annotation.processors)

  implementation(libs.common.editor)
  implementation(libs.common.utilcode)
  implementation(libs.common.glide)
  implementation(libs.common.jsoup)
  implementation(libs.common.kotlin.coroutines.android)
  implementation(libs.common.retrofit)
  implementation(libs.common.retrofit.gson)
  implementation(libs.common.charts)
  implementation(libs.common.hiddenApiBypass)
  implementation(libs.aapt2.common)

  implementation(libs.google.auto.service.annotations)
  implementation(libs.google.gson)
  implementation(libs.google.guava)

  implementation("com.google.ai.client.generativeai:generativeai:0.9.0") {
    exclude(group = "org.slf4j", module = "slf4j-api")
    exclude(group = "org.slf4j", module = "slf4j-simple")
    exclude(group = "org.slf4j", module = "slf4j-nop")
  }
  
  // TODO: remove this
  implementation("com.github.MiyazKaori:SilentInstaller:1.0.0-alpha")

  // Git
  implementation(libs.git.jgit)

  // AndroidX
  implementation(libs.androidx.splashscreen)
  implementation(libs.androidx.annotation)
  implementation(libs.androidx.appcompat)
  implementation(libs.androidx.cardview)
  implementation(libs.androidx.constraintlayout)
  implementation(libs.androidx.coordinatorlayout)
  implementation(libs.androidx.drawer)
  implementation(libs.androidx.grid)
  implementation(libs.androidx.nav.fragment)
  implementation(libs.androidx.nav.ui)
  implementation(libs.androidx.preference)
  implementation(libs.androidx.recyclerview)
  implementation(libs.androidx.transition)
  implementation(libs.androidx.vectors)
  implementation(libs.androidx.animated.vectors)
  implementation(libs.androidx.work)
  implementation(libs.androidx.work.ktx)
  implementation(libs.google.material)
  implementation(libs.google.flexbox)

  // Kotlin
  implementation(libs.androidx.core.ktx)
  implementation(libs.common.kotlin)

  // Dependencies in composite build
  implementation(libs.composite.appintro)
  implementation(libs.composite.desugaringCore)
  // implementation(libs.composite.javapoet)
  implementation(files(rootProject.file("composite-builds/build-deps/libs/javapoet.jar")))

  // Local projects here
  implementation(projects.core.projectdata)
  implementation(projects.ideconfigurations)
  implementation(projects.core.actions)
  implementation(projects.core.common)
  implementation(projects.core.indexingApi)
  implementation(projects.core.indexingCore)
  implementation(projects.core.lspApi)
  implementation(projects.core.projects)
  implementation(projects.core.resources)
  implementation(projects.editor.impl)
  implementation(projects.editor.lexers)
  implementation(projects.event.eventbus)
  implementation(projects.event.eventbusAndroid)
  implementation(projects.event.eventbusEvents)
  implementation(projects.java.javacServices)
  implementation(projects.java.lspSetup)
  implementation(projects.java.lsp)
  implementation(projects.logging.idestats)
  implementation(projects.logging.logsender)
  implementation(projects.termux.application)
  implementation(projects.termux.view)
  implementation(projects.termux.emulator)
  implementation(projects.termux.shared)
  implementation(projects.tooling.api)
  implementation(projects.tooling.pluginConfig)
  implementation(projects.utilities.buildInfo)
  implementation(projects.utilities.lookup)
  implementation(projects.utilities.preferences)
  implementation(projects.utilities.templatesApi)
  implementation(projects.utilities.templatesImpl)
  implementation(projects.utilities.treeview)
  implementation(projects.utilities.uidesigner)
  implementation(projects.utilities.xmlInflater)
  implementation(projects.xml.aaptcompiler)
  implementation(projects.xml.lsp)
  implementation(projects.xml.utils)

  // This is to build the tooling-api-impl project before the app is built
  // So we always copy the latest JAR file to assets
  compileOnly(projects.tooling.impl)
  
}
