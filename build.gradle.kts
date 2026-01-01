plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.1.21" apply false
    id("org.jetbrains.intellij.platform") version "2.10.2" apply false
}

allprojects {
    group = "info.jiayun.intellijmcp"
    version = "1.3.0"

    repositories {
        mavenCentral()
    }
}

subprojects {
    apply(plugin = "java")
    apply(plugin = "org.jetbrains.kotlin.jvm")

    java {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        kotlinOptions {
            jvmTarget = "21"
            freeCompilerArgs = listOf("-Xjsr305=strict", "-Xskip-metadata-version-check")
        }
    }
}
