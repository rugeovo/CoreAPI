import io.izzel.taboolib.gradle.Basic
import io.izzel.taboolib.gradle.Bukkit
import io.izzel.taboolib.gradle.BukkitHook
import io.izzel.taboolib.gradle.BukkitNMSUtil
import org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile


plugins {
    java
    id("io.izzel.taboolib") version "2.0.27"
    id("org.jetbrains.kotlin.jvm") version "2.2.0"
}

taboolib {
    env {
        install(Basic, Bukkit, BukkitHook, BukkitNMSUtil)
    }
    version {
        taboolib = "6.2.4-65252583"
        coroutines = "1.8.1"
    }
}

repositories {
    mavenCentral()
    maven("https://repo.codemc.io/repository/maven-public/") // AuthMe
}

dependencies {
    compileOnly("ink.ptms.core:v12105:12105-minimize:mapped@jar")
    compileOnly(kotlin("stdlib"))
    compileOnly(fileTree("libs"))

    // HTTP服务器 - Jetty（企业级，安全更新及时）
    taboo("org.eclipse.jetty:jetty-server:11.0.20")
    taboo("org.eclipse.jetty:jetty-servlet:11.0.20")

    // 缓存和限流
    taboo("com.google.guava:guava:32.1.3-jre")

    // JSON处理
    implementation("com.google.code.gson:gson:2.10.1")
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}

tasks.withType<KotlinCompile> {
    compilerOptions {
        jvmTarget.set(JVM_1_8)
        freeCompilerArgs.add("-Xjvm-default=all")
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}
