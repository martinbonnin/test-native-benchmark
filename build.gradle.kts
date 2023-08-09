plugins {
  id("org.jetbrains.kotlin.multiplatform").version("1.9.0")
  id("com.apollographql.apollo3").version("4.0.0-alpha.3")
}


kotlin {
  macosArm64()

  sourceSets {
    getByName("commonMain") {
      dependencies {
      }
    }

    getByName("commonTest") {
      dependencies {
        val ktor_version = "2.3.3"
        implementation("io.ktor:ktor-server-core:$ktor_version")
        implementation("io.ktor:ktor-server-cio:$ktor_version")
        implementation("com.apollographql.apollo3:apollo-runtime")
        implementation("com.squareup.okio:okio:3.2.0")
        implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
        implementation("org.jetbrains.kotlin:kotlin-test")
      }
    }
  }
}


//apollo {
//  service("service") {
//    packageName.set("benchmarks")
//    generateDataBuilders.set(true)
//  }
//}
