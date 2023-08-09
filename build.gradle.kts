plugins {
  id("org.jetbrains.kotlin.multiplatform").version("1.9.0")
  id("com.apollographql.apollo3").version("4.0.0-alpha.3")
}


kotlin {
  macosArm64()

  sourceSets {
    getByName("commonMain") {
      dependencies {
        implementation("com.apollographql.apollo3:apollo-runtime")
        implementation("com.apollographql.apollo3:apollo-testing-support")
        implementation("com.apollographql.apollo3:apollo-normalized-cache")
        implementation("com.apollographql.apollo3:apollo-mockserver")
      }
    }

    getByName("commonTest") {
      dependencies {
        implementation("org.jetbrains.kotlin:kotlin-test")
      }
    }
  }
}


apollo {
  service("service") {
    packageName.set("benchmarks")
    generateDataBuilders.set(true)
  }
}
