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
    description{
        dependencies{
            name("LuckPerms").with("bukkit").optional(true).loadafter(true)
            name("AuthMe").with("bukkit").optional(true).loadafter(true)
        }
    }
}

repositories {
    mavenCentral()
    maven("https://repo.codemc.org/repository/maven-public/") // AuthMe
    maven("https://oss.sonatype.org/content/repositories/snapshots") // LuckPerms
}

dependencies {
    compileOnly("ink.ptms.core:v12105:12105-minimize:mapped@jar")
    compileOnly(kotlin("stdlib"))
    compileOnly(fileTree("libs"))

    // AuthMe 认证API
    compileOnly("fr.xephi:authme:5.6.1-SNAPSHOT")

    // LuckPerms 权限API
    compileOnly("net.luckperms:api:5.4")

    // JWT (JSON Web Token) 支持
    taboo("io.jsonwebtoken:jjwt-api:0.12.3")
    taboo("io.jsonwebtoken:jjwt-impl:0.12.3")
    taboo("io.jsonwebtoken:jjwt-jackson:0.12.3")

    // HTTP服务器 - Jetty（企业级，安全更新及时）
    taboo("org.eclipse.jetty:jetty-server:11.0.20")
    taboo("org.eclipse.jetty:jetty-servlet:11.0.20")

    // 缓存和限流
    taboo("com.google.guava:guava:32.1.3-jre")

    // JSON处理
    implementation("com.google.code.gson:gson:2.10.1")
    
    // 测试依赖
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
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

tasks.test {
    useJUnitPlatform()
}
