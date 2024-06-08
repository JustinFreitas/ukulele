import org.springframework.boot.gradle.tasks.bundling.BootJar

plugins {
    val kotlinVersion = "2.0.0"
    java
    // https://plugins.gradle.org/plugin/org.springframework.boot
    id("org.springframework.boot") version "3.3.0"
    // https://plugins.gradle.org/plugin/org.jetbrains.kotlin.jvm
    kotlin("jvm") version kotlinVersion
    // https://plugins.gradle.org/plugin/org.jetbrains.kotlin.plugin.spring
    kotlin("plugin.spring") version kotlinVersion
    // https://plugins.gradle.org/plugin/org.flywaydb.flyway
	id("org.flywaydb.flyway") version "10.14.0"
}

kotlin {
    jvmToolchain(17)
}

repositories {
    //mavenLocal()
    mavenCentral()
    // snapshots are available under '/snapshots'
    maven {
        name = "arbjergDevReleases"
        url = uri("https://maven.lavalink.dev/releases")
    }
    maven { url = uri("https://jitpack.io") }
}

dependencies {
    // Required for BotProps
    val springVersion = "3.3.0"
    // https://mvnrepository.com/artifact/org.springframework.boot/spring-boot-configuration-processor
    implementation("org.springframework.boot:spring-boot-configuration-processor:$springVersion")
    // https://mvnrepository.com/artifact/org.springframework.boot/spring-boot-starter-data-r2dbc
    implementation("org.springframework.boot:spring-boot-starter-data-r2dbc:$springVersion")
    // https://mvnrepository.com/artifact/org.yaml/snakeyaml
    implementation("org.yaml:snakeyaml:2.2")

    // https://mvnrepository.com/artifact/net.dv8tion/JDA
    // https://github.com/discord-jda/JDA
    implementation("net.dv8tion:JDA:5.0.0-beta.24")

    // Jocull has latest Ukulele we should pattern after in JDA-5 branch.
    // https://github.com/jocull/ukulele/tree/jda-5
    // https://github.com/lavalink-devs/lavaplayer
    // implementation("dev.arbjerg:lavaplayer:2.1.1")
    // Above broken for YouTube links.  Use Justin's fork.
    // https://github.com/JustinFreitas/lavaplayer
    implementation("com.github.justinfreitas:lavaplayer:v2.2.0_1")
    // https://github.com/lavalink-devs/youtube-source
    implementation("dev.lavalink.youtube:v2:1.3.0")
    implementation("dev.lavalink.youtube:common:1.3.0")
    implementation("dev.lavalink.youtube:youtube-plugin:1.3.0")
    constraints {
        // https://mvnrepository.com/artifact/commons-codec/commons-codec
        implementation("commons-codec:commons-codec:1.17.0") {
            because("Apache commons-codec before 1.13 is vulnerable to information exposure.")
        }
    }

    // https://mvnrepository.com/artifact/commons-io/commons-io
    implementation("commons-io:commons-io:2.16.1")
    // https://mvnrepository.com/artifact/org.json/json
    implementation("org.json:json:20240303")
    // https://mvnrepository.com/artifact/org.jsoup/jsoup
    implementation("org.jsoup:jsoup:1.17.2")

    val jacksonVersion = "2.17.1"
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
    implementation("org.flywaydb:flyway-core:10.14.0")
    // https://mvnrepository.com/artifact/com.github.ben-manes.caffeine/caffeine
    implementation("com.github.ben-manes.caffeine:caffeine:3.1.8")

    val kotlinVersion = "2.0.0"
    // https://mvnrepository.com/artifact/org.jetbrains.kotlin/kotlin-reflect
    implementation("org.jetbrains.kotlin:kotlin-reflect:$kotlinVersion")
    // https://mvnrepository.com/artifact/org.jetbrains.kotlin/kotlin-stdlib-jdk8
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8:$kotlinVersion")
    // https://mvnrepository.com/artifact/org.jetbrains.kotlinx/kotlinx-coroutines-reactor
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor:1.9.0-RC")
    // https://mvnrepository.com/artifact/io.projectreactor.kotlin/reactor-kotlin-extensions
    implementation("io.projectreactor.kotlin:reactor-kotlin-extensions:1.2.2")
    // https://mvnrepository.com/artifact/org.springframework.boot/spring-boot-starter-test
    testImplementation("org.springframework.boot:spring-boot-starter-test:$springVersion") {
        exclude(group = "org.junit.vintage", module = "junit-vintage-engine")
    }
}

configurations.all {
    resolutionStrategy.dependencySubstitution {
        substitute(module("org.jetbrains:annotations:13.0")).using(module("org.jetbrains:annotations:23.0.0"))
        substitute(module("net.bytebuddy:byte-buddy:1.14.11")).using(module("net.bytebuddy:byte-buddy:1.14.12"))
        substitute(module("commons-codec:commons-codec:1.11")).using(module("commons-codec:commons-codec:1.17.0"))
        substitute(module("commons-io:commons-io:2.13.0")).using(module("commons-io:commons-io:2.16.1"))
        substitute(module("org.hamcrest:hamcrest:2.1")).using(module("org.hamcrest:hamcrest:2.2"))
        substitute(module("com.fasterxml.jackson.core:jackson-core:2.15.2")).using(module("com.fasterxml.jackson.core:jackson-core:2.17.1"))
        substitute(module("com.fasterxml.jackson.core:jackson-core:2.17.0")).using(module("com.fasterxml.jackson.core:jackson-core:2.17.1"))
        substitute(module("com.fasterxml.jackson.dataformat:jackson-dataformat-toml:2.15.2")).using(module("com.fasterxml.jackson.dataformat:jackson-dataformat-toml:2.17.1"))
        substitute(module("com.fasterxml.jackson.core:jackson-databind:2.15.2")).using(module("com.fasterxml.jackson.core:jackson-databind:2.17.1"))
        substitute(module("com.fasterxml.jackson.core:jackson-databind:2.17.0")).using(module("com.fasterxml.jackson.core:jackson-databind:2.17.1"))
        substitute(module("net.minidev:json-smart:2.5.0")).using(module("net.minidev:json-smart:2.5.1"))
        substitute(module("org.jsoup:jsoup:1.16.1")).using(module("org.jsoup:jsoup:1.17.2"))
        substitute(module("org.jetbrains.kotlin:kotlin-stdlib-common:1.9.10")).using(module("org.jetbrains.kotlin:kotlin-stdlib-common:2.0.0"))
        substitute(module("org.jetbrains.kotlin:kotlin-stdlib-common:1.9.21")).using(module("org.jetbrains.kotlin:kotlin-stdlib-common:2.0.0"))
        substitute(module("org.jetbrains.kotlin:kotlin-stdlib-jdk7:1.8.0")).using(module("org.jetbrains.kotlin:kotlin-stdlib-jdk7:2.0.0"))
        substitute(module("org.jetbrains.kotlin:kotlin-stdlib-jdk8:1.8.21")).using(module("org.jetbrains.kotlin:kotlin-stdlib-jdk8:2.0.0"))
        substitute(module("org.jetbrains.kotlin:kotlin-stdlib:1.9.21")).using(module("org.jetbrains.kotlin:kotlin-stdlib:2.0.0"))
        substitute(module("org.reactivestreams:reactive-streams:1.0.3")).using(module("org.reactivestreams:reactive-streams:1.0.4"))
        substitute(module("org.mozilla:rhino-engine:1.7.14")).using(module("org.mozilla:rhino-engine:1.7.15"))
        substitute(module("org.springframework:spring-beans:6.1.7")).using(module("org.springframework:spring-beans:6.1.8"))
        substitute(module("org.springframework:spring-context:6.1.7")).using(module("org.springframework:spring-context:6.1.8"))
        substitute(module("org.springframework:spring-core:6.1.7")).using(module("org.springframework:spring-core:6.1.8"))
    }
}

tasks.withType<BootJar> {
    archiveFileName.set("ukulele.jar")
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
    doLast {
        //copies the jar into a place where the Dockerfile can find it easily (and users maybe too)
        copy {
            from("build/libs/ukulele.jar")
            into(".")
        }
    }
}
