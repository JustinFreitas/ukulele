import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.springframework.boot.gradle.tasks.bundling.BootJar

plugins {
    val kotlinVersion = "1.9.20-Beta2"
    java
    // https://plugins.gradle.org/plugin/org.springframework.boot
    id("org.springframework.boot") version "3.1.4"
    // https://plugins.gradle.org/plugin/org.jetbrains.kotlin.jvm
    kotlin("jvm") version kotlinVersion
    // https://plugins.gradle.org/plugin/org.jetbrains.kotlin.plugin.spring
    kotlin("plugin.spring") version kotlinVersion
}

apply(plugin = "io.spring.dependency-management")
group = "dev.arbjerg"
version = "0.1"
java.sourceCompatibility = JavaVersion.VERSION_17

repositories {
    //mavenLocal()
    mavenCentral()
    maven { url = uri("https://jitpack.io") }
}

dependencies {
    // Required for BotProps
    val springVersion = "3.1.4"
    // https://mvnrepository.com/artifact/org.springframework.boot/spring-boot-configuration-processor
    implementation("org.springframework.boot:spring-boot-configuration-processor:$springVersion")
    // https://mvnrepository.com/artifact/org.springframework.boot/spring-boot-starter-data-r2dbc
    implementation("org.springframework.boot:spring-boot-starter-data-r2dbc:$springVersion")
    // https://mvnrepository.com/artifact/org.yaml/snakeyaml
    implementation("org.yaml:snakeyaml:2.2")

    // https://mvnrepository.com/artifact/net.dv8tion/JDA
    // https://github.com/discord-jda/JDA
    implementation("net.dv8tion:JDA:5.0.0-beta.15")

    // https://mvnrepository.com/artifact/com.github.walkyst.lavaplayer-fork/lavaplayer
    // https://github.com/Walkyst/lavaplayer-fork
    implementation("com.github.walkyst.lavaplayer-fork:lavaplayer:1.4.3")
    // https://mvnrepository.com/artifact/org.apache.httpcomponents/httpclient
    implementation("org.apache.httpcomponents:httpclient:4.5.14")  // Lavaplayer uses 4.5.10, which has vuln.
    // https://mvnrepository.com/artifact/commons-io/commons-io
    implementation("commons-io:commons-io:2.14.0")
    // https://mvnrepository.com/artifact/org.json/json
    implementation("org.json:json:20230618")
    // https://mvnrepository.com/artifact/org.jsoup/jsoup
    implementation("org.jsoup:jsoup:1.16.1")

    val jacksonVersion = "2.15.2"
    // https://mvnrepository.com/artifact/com.fasterxml.jackson.core/jackson-core
    implementation("com.fasterxml.jackson.core:jackson-core:$jacksonVersion")
    // https://mvnrepository.com/artifact/com.fasterxml.jackson.core/jackson-databind
    implementation("com.fasterxml.jackson.core:jackson-databind:$jacksonVersion")
    // https://mvnrepository.com/artifact/com.fasterxml.jackson.core/jackson-annotations
    implementation("com.fasterxml.jackson.core:jackson-annotations:$jacksonVersion")

    // https://mvnrepository.com/artifact/com.h2database/h2
    runtimeOnly("com.h2database:h2:2.2.224")
    // https://mvnrepository.com/artifact/io.r2dbc/r2dbc-h2
    implementation("io.r2dbc:r2dbc-h2:1.0.0.RELEASE") {
        exclude(group = "com.h2database", module = "h2")
    }
    // https://mvnrepository.com/artifact/org.flywaydb/flyway-core
    implementation("org.flywaydb:flyway-core:9.22.2")
    // https://mvnrepository.com/artifact/com.github.ben-manes.caffeine/caffeine
    implementation("com.github.ben-manes.caffeine:caffeine:3.1.8")

    val kotlinVersion = "1.9.20-Beta2"
    // https://mvnrepository.com/artifact/org.jetbrains.kotlin/kotlin-reflect
    implementation("org.jetbrains.kotlin:kotlin-reflect:$kotlinVersion")
    // https://mvnrepository.com/artifact/org.jetbrains.kotlin/kotlin-stdlib-jdk8
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8:$kotlinVersion")
    // https://mvnrepository.com/artifact/org.jetbrains.kotlinx/kotlinx-coroutines-reactor
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor:1.7.3")
    // https://mvnrepository.com/artifact/io.projectreactor.kotlin/reactor-kotlin-extensions
    implementation("io.projectreactor.kotlin:reactor-kotlin-extensions:1.2.2")
    // https://mvnrepository.com/artifact/org.springframework.boot/spring-boot-starter-test
    testImplementation("org.springframework.boot:spring-boot-starter-test:$springVersion") {
        exclude(group = "org.junit.vintage", module = "junit-vintage-engine")
    }
}

tasks.withType<BootJar> {
    archiveFileName.set("ukulele.jar")
    doLast {
        //copies the jar into a place where the Dockerfile can find it easily (and users maybe too)
        copy {
            from("build/libs/ukulele.jar")
            into(".")
        }
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}

tasks.withType<KotlinCompile> {
    kotlinOptions {
        freeCompilerArgs = listOf("-Xjsr305=strict",
                                  "-opt-in=kotlin.RequiresOptIn")
        jvmTarget = "17"
    }
}
