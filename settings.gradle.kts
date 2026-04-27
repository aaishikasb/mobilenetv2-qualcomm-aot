pluginManagement {
  repositories {
    google {
      content {
        includeGroupByRegex("com\\.android.*")
        includeGroupByRegex("com\\.google.*")
        includeGroupByRegex("androidx.*")
      }
    }
    mavenCentral()
    gradlePluginPortal()
  }
}

dependencyResolutionManagement {
  repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
  repositories {
    google()
    mavenCentral()
  }
}

rootProject.name = "LiteRT MobileNet AOT"

include(":app")

val enableNpu =
  providers.gradleProperty("enableNpu").map(String::toBoolean).orElse(false).get()

/*
if (enableNpu) {
  val aiPackModule = file("ai_pack/mobilenet_v2_sm8750/build.gradle.kts")
  if (!aiPackModule.exists()) {
    throw GradleException(
      "NPU build requested, but ai_pack/mobilenet_v2_sm8750 is missing. " +
        "Run tools/prepare_aot_sm8750.py first."
    )
  }
  include(":ai_pack:mobilenet_v2_sm8750")

  val runtimeModules =
    listOf(
      ":litert_npu_runtime_libraries:runtime_strings",
      ":litert_npu_runtime_libraries:qualcomm_runtime_v81",
    )

  runtimeModules.forEach { module ->
    val modulePath = module.removePrefix(":").replace(":", "/")
    if (!file("$modulePath/build.gradle.kts").exists() && !file("$modulePath/build.gradle").exists()) {
      throw GradleException(
        "NPU build requested, but $modulePath is missing. " +
          "Run tools/prepare_aot_sm8750.py to download LiteRT NPU runtime libraries."
      )
    }
    include(module)
  }
}
*/
