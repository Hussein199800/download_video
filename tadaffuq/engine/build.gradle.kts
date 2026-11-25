plugins {
    alias(libs.plugins.kotlin.jvm)
}
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencies {
    api(libs.coroutines.core)
    api(libs.okhttp)
    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.coroutines.test)
}
tasks.test { testLogging { events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL } }
