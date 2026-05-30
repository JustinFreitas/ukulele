import org.springframework.boot.gradle.tasks.bundling.BootJar

plugins {
    val kotlinVersion = "2.3.21"
    java
    // https://plugins.gradle.org/plugin/org.springframework.boot
    id("org.springframework.boot") version "4.0.6"
    // https://plugins.gradle.org/plugin/org.jetbrains.kotlin.jvm
    kotlin("jvm") version kotlinVersion
    // https://plugins.gradle.org/plugin/org.jetbrains.kotlin.plugin.spring
    kotlin("plugin.spring") version kotlinVersion
    // https://plugins.gradle.org/plugin/org.flywaydb.flyway
    id("org.flywaydb.flyway") version "12.5.0"
    // https://plugins.gradle.org/plugin/com.github.ben-manes.versions
    id("com.github.ben-manes.versions") version "0.54.0"
    id("org.jlleitschuh.gradle.ktlint") version "12.1.2"
}

tasks.withType<com.github.benmanes.gradle.versions.updates.DependencyUpdatesTask> {
    rejectVersionIf {
        val stableKeyword = listOf("RELEASE", "FINAL", "GA").any { candidate.version.uppercase().contains(it) }
        val regex = "^[0-9,.v-]+(-r)?$".toRegex()
        val isStable = stableKeyword || regex.matches(candidate.version)
        !isStable
    }
}

// Version Constants
val kotlinVersion = "2.3.21"
val springBootVersion = "4.0.6"
val springFrameworkVersion = "7.0.7"
val jacksonVersion = "3.1.2"
// https://mvnrepository.com/artifact/com.fasterxml.jackson.core/jackson-annotations
val jacksonAnnotationsVersion = "2.21"
val nettyVersion = "4.2.12.Final"
val jdaveVersion = "0.1.8"

kotlin {
    jvmToolchain(25)
}

group = "dev.arbjerg"
version = "0.1-SNAPSHOT"

repositories {
    // mavenLocal()
    mavenCentral()
    // snapshots are available under '/snapshots'
    maven {
        name = "arbjergDevReleases"
        url = uri("https://maven.lavalink.dev/releases")
    }
    maven {
        name = "arbjergDevSnapshots"
        url = uri("https://maven.lavalink.dev/snapshots")
    }
    maven { url = uri("https://jitpack.io") }
}

dependencies {
    // https://mvnrepository.com/artifact/org.springframework.boot/spring-boot-configuration-processor
    implementation("org.springframework.boot:spring-boot-configuration-processor:$springBootVersion")
    // https://mvnrepository.com/artifact/org.springframework.boot/spring-boot-starter-data-r2dbc
    implementation("org.springframework.boot:spring-boot-starter-data-r2dbc:$springBootVersion")
    // https://mvnrepository.com/artifact/org.springframework.boot/spring-boot-starter-webflux
    implementation("org.springframework.boot:spring-boot-starter-webflux:$springBootVersion")
    // https://mvnrepository.com/artifact/org.springframework.boot/spring-boot-starter-web
    implementation("org.springframework.boot:spring-boot-starter-web:$springBootVersion")
    // https://mvnrepository.com/artifact/org.springframework.boot/spring-boot-starter-websocket
    implementation("org.springframework.boot:spring-boot-starter-websocket:$springBootVersion")
    // https://mvnrepository.com/artifact/org.yaml/snakeyaml
    implementation("org.yaml:snakeyaml:2.6")

    // https://mvnrepository.com/artifact/net.dv8tion/JDA
    // https://github.com/discord-jda/JDA
    implementation("net.dv8tion:JDA:6.4.1")

    // Jocull has the latest Ukulele we should pattern after in JDA-5 branch.
    // https://github.com/jocull/ukulele/tree/jda-5
    // https://github.com/lavalink-devs/lavaplayer
    // implementation("dev.arbjerg:lavaplayer:2.2.6")
    // Above broken for YouTube links.  Use Justin's fork.
    // https://github.com/JustinFreitas/lavaplayer
    implementation("com.github.JustinFreitas:lavaplayer:v2.2.6_11")
    // https://mvnrepository.com/artifact/club.minnced/jdave-api
    implementation("club.minnced:jdave-api:$jdaveVersion")
    implementation("club.minnced:jdave-native-win-x86-64:$jdaveVersion")
    implementation("club.minnced:jdave-native-linux-x86-64:$jdaveVersion")
    // https://github.com/lavalink-devs/youtube-source
    implementation("dev.lavalink.youtube:v2:1.18.1")
    implementation("dev.lavalink.youtube:common:1.18.1")
    implementation("dev.lavalink.youtube:youtube-plugin:1.18.1")
    // https://mvnrepository.com/artifact/commons-io/commons-io
    implementation("commons-io:commons-io:2.21.0")
    // https://mvnrepository.com/artifact/org.json/json
    implementation("org.json:json:20251224")
    // https://mvnrepository.com/artifact/org.jsoup/jsoup
    implementation("org.jsoup:jsoup:1.22.1")

    // https://mvnrepository.com/artifact/com.google.code.gson/gson
    implementation("com.google.code.gson:gson:2.13.2")

    // https://mvnrepository.com/artifact/org.apache.httpcomponents/httpclient
    implementation("org.apache.httpcomponents:httpclient:4.5.14")
    // https://mvnrepository.com/artifact/org.apache.httpcomponents.client5/httpclient5
    implementation("org.apache.httpcomponents.client5:httpclient5:5.6.1")
    // Explicit httpcore5 to access SocketConfig
    implementation("org.apache.httpcomponents.core5:httpcore5:5.4.2")

    // https://mvnrepository.com/artifact/com.fasterxml.jackson.core/jackson-core
    implementation("tools.jackson.core:jackson-core:$jacksonVersion")
    // https://mvnrepository.com/artifact/com.fasterxml.jackson.core/jackson-databind
    implementation("tools.jackson.core:jackson-databind:$jacksonVersion")
    // https://mvnrepository.com/artifact/com.fasterxml.jackson.core/jackson-annotations
    implementation("com.fasterxml.jackson.core:jackson-annotations:$jacksonAnnotationsVersion")

    // https://mvnrepository.com/artifact/com.h2database/h2
    // NOTE: 2.4.240 caused a problem where Ukulele couldn't join the channel for some reason.
    // UPDATE: 20251213 - I think since both of these deps are updated now (2.4.240 and 1.1.0), we can try the DB update
    // again and see if we can get everything to work now.
    runtimeOnly("com.h2database:h2:2.4.240")
    // https://mvnrepository.com/artifact/io.r2dbc/r2dbc-h2
    implementation("io.r2dbc:r2dbc-h2:1.1.0.RELEASE") {
        exclude(group = "com.h2database", module = "h2")
    }
    // https://mvnrepository.com/artifact/org.flywaydb/flyway-core
    implementation("org.flywaydb:flyway-core:12.5.0")
    // https://mvnrepository.com/artifact/com.github.ben-manes.caffeine/caffeine
    implementation("com.github.ben-manes.caffeine:caffeine:3.2.4")

    // https://mvnrepository.com/artifact/org.jetbrains.kotlin/kotlin-reflect
    implementation("org.jetbrains.kotlin:kotlin-reflect:$kotlinVersion")
    // https://mvnrepository.com/artifact/org.jetbrains.kotlin/kotlin-stdlib-jdk8
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8:$kotlinVersion")
    // https://mvnrepository.com/artifact/org.jetbrains.kotlinx/kotlinx-coroutines-reactor
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor:1.10.2")
    // https://mvnrepository.com/artifact/io.projectreactor.kotlin/reactor-kotlin-extensions
    implementation("io.projectreactor.kotlin:reactor-kotlin-extensions:1.3.0")
    // https://mvnrepository.com/artifact/org.springframework.boot/spring-boot-starter-test
    testImplementation("org.springframework.boot:spring-boot-starter-test:$springBootVersion") {
        exclude(group = "org.junit.vintage", module = "junit-vintage-engine")
    }

    // https://mvnrepository.com/artifact/org.junit.jupiter/junit-jupiter
    testImplementation("org.junit.jupiter:junit-jupiter:5.14.3")
}

val mockitoAgent: Configuration by configurations.creating

dependencies {
    mockitoAgent("net.bytebuddy:byte-buddy-agent:1.18.8") {
        isTransitive = false
    }
}

configurations.all {
    resolutionStrategy.dependencySubstitution {
        // logback issue requiring both substitutions: https://github.com/qos-ch/logback/issues/890
        // https://mvnrepository.com/artifact/tools.jackson.core/jackson-core
        substitute(module("tools.jackson:jackson-bom")).using(module("tools.jackson:jackson-bom:$jacksonVersion"))
        substitute(module("tools.jackson.core:jackson-core")).using(module("tools.jackson.core:jackson-core:$jacksonVersion"))
        substitute(module("tools.jackson.core:jackson-databind")).using(module("tools.jackson.core:jackson-databind:$jacksonVersion"))
        substitute(
            module("tools.jackson.dataformat:jackson-dataformat-toml"),
        ).using(module("tools.jackson.dataformat:jackson-dataformat-toml:$jacksonVersion"))
        // jackson-datatype-jsr310 consolidated in 3.0 databind, but for legacy transitive overrides:
        substitute(
            module("tools.jackson.datatype:jackson-datatype-jsr310"),
        ).using(module("tools.jackson.core:jackson-databind:$jacksonVersion"))

        // Legacy 2.x Substitutions (to keep JDA and others happy on the same classpath)
        substitute(
            module("com.fasterxml.jackson.core:jackson-annotations"),
        ).using(module("com.fasterxml.jackson.core:jackson-annotations:$jacksonAnnotationsVersion"))
        substitute(module("com.fasterxml.jackson.core:jackson-core")).using(module("com.fasterxml.jackson.core:jackson-core:2.21.2"))
        substitute(
            module("com.fasterxml.jackson.core:jackson-databind"),
        ).using(module("com.fasterxml.jackson.core:jackson-databind:2.21.2"))
        // https://mvnrepository.com/artifact/com.google.code.gson/gson
        substitute(module("com.google.code.gson:gson")).using(module("com.google.code.gson:gson:2.13.2"))
        substitute(
            module("com.google.errorprone:error_prone_annotations"),
        ).using(module("com.google.errorprone:error_prone_annotations:2.36.0"))
        // https://mvnrepository.com/artifact/commons-codec/commons-codec
        substitute(module("commons-codec:commons-codec")).using(module("commons-codec:commons-codec:1.21.0"))
        // https://mvnrepository.com/artifact/commons-io/commons-io
        substitute(module("commons-io:commons-io")).using(module("commons-io:commons-io:2.21.0"))
        substitute(module("net.bytebuddy:byte-buddy")).using(module("net.bytebuddy:byte-buddy:1.18.8"))
        substitute(module("net.bytebuddy:byte-buddy-agent")).using(module("net.bytebuddy:byte-buddy-agent:1.18.8"))
        substitute(module("net.minidev:json-smart")).using(module("net.minidev:json-smart:2.5.2"))
        substitute(module("org.assertj:assertj-core")).using(module("org.assertj:assertj-core:3.27.7"))
        substitute(module("org.checkerframework:checker-qual")).using(module("org.checkerframework:checker-qual:2.43.0"))
        substitute(module("org.hamcrest:hamcrest:2.1")).using(module("org.hamcrest:hamcrest:3.0"))
//        // https://mvnrepository.com/artifact/org.jetbrains/annotations
        substitute(module("org.jetbrains:annotations")).using(module("org.jetbrains:annotations:26.1.0"))
        // https://mvnrepository.com/artifact/org.jetbrains.kotlin/kotlin-reflect
        substitute(
            module("org.jetbrains.kotlin:kotlin-stdlib-common"),
        ).using(module("org.jetbrains.kotlin:kotlin-stdlib-common:$kotlinVersion"))
        substitute(
            module("org.jetbrains.kotlin:kotlin-stdlib-jdk7"),
        ).using(module("org.jetbrains.kotlin:kotlin-stdlib-jdk7:$kotlinVersion"))
        substitute(
            module("org.jetbrains.kotlin:kotlin-stdlib-jdk8"),
        ).using(module("org.jetbrains.kotlin:kotlin-stdlib-jdk8:$kotlinVersion"))
        substitute(module("org.jetbrains.kotlin:kotlin-stdlib")).using(module("org.jetbrains.kotlin:kotlin-stdlib:$kotlinVersion"))
        // https://mvnrepository.com/artifact/org.json/json
        substitute(module("org.json:json")).using(module("org.json:json:20251224"))
        // https://mvnrepository.com/artifact/org.jsoup/jsoup
        substitute(module("org.jsoup:jsoup")).using(module("org.jsoup:jsoup:1.22.1"))
        // https://mvnrepository.com/artifact/org.junit.jupiter/junit-jupiter
        substitute(module("org.junit.jupiter:junit-jupiter")).using(module("org.junit.jupiter:junit-jupiter:5.14.3"))
        substitute(module("org.junit.jupiter:junit-jupiter-api")).using(module("org.junit.jupiter:junit-jupiter-api:5.14.3"))
        // https://mvnrepository.com/artifact/org.mozilla/rhino-engine
        substitute(module("org.mozilla:rhino-engine")).using(module("org.mozilla:rhino-engine:1.9.1"))
        // https://mvnrepository.com/artifact/org.reactivestreams/reactive-streams
        substitute(module("org.reactivestreams:reactive-streams")).using(module("org.reactivestreams:reactive-streams:1.0.4"))
        // https://mvnrepository.com/artifact/org.slf4j/slf4j-api
        substitute(module("org.slf4j:slf4j-api")).using(module("org.slf4j:slf4j-api:2.0.17"))
        // https://mvnrepository.com/artifact/org.springframework/spring-core
        substitute(module("org.springframework:spring-aop")).using(module("org.springframework:spring-aop:$springFrameworkVersion"))
        substitute(module("org.springframework:spring-beans")).using(module("org.springframework:spring-beans:$springFrameworkVersion"))
        substitute(module("org.springframework:spring-context")).using(module("org.springframework:spring-context:$springFrameworkVersion"))
        substitute(module("org.springframework:spring-core")).using(module("org.springframework:spring-core:$springFrameworkVersion"))
        substitute(
            module("org.springframework:spring-expression"),
        ).using(module("org.springframework:spring-expression:$springFrameworkVersion"))
        substitute(
            module("org.springframework:spring-messaging"),
        ).using(module("org.springframework:spring-messaging:$springFrameworkVersion"))
        substitute(module("org.springframework:spring-web")).using(module("org.springframework:spring-web:$springFrameworkVersion"))
        substitute(
            module("org.springframework:spring-websocket"),
        ).using(module("org.springframework:spring-websocket:$springFrameworkVersion"))

        // --- H2 Database Hard Overrides ---
        // We declare the true versions up in the `dependencies {}` block above to keep it clean and idiomatic.
        // However, the Spring Boot plugin (3.5.x) imports its own internal BOM which can silently downgrade these
        // dependencies backwards against our will if its internal libraries ask for an older 2.3.x schema driver.
        // We use this dependencySubstitution block as a failsafe to forcefully guarantee 2.4.x is locked in regardless.
        substitute(module("com.h2database:h2")).using(module("com.h2database:h2:2.4.240"))
        substitute(module("io.r2dbc:r2dbc-h2")).using(module("io.r2dbc:r2dbc-h2:1.1.0.RELEASE"))
        // https://mvnrepository.com/artifact/io.micrometer/micrometer-observation
        substitute(module("io.micrometer:micrometer-observation")).using(module("io.micrometer:micrometer-observation:1.16.4"))
        // https://mvnrepository.com/artifact/org.yaml/snakeyaml
        substitute(module("org.yaml:snakeyaml")).using(module("org.yaml:snakeyaml:2.6"))
        // https://mvnrepository.com/artifact/io.netty/netty-codec
        substitute(module("io.netty:netty-buffer")).using(module("io.netty:netty-buffer:$nettyVersion"))
        substitute(module("io.netty:netty-codec")).using(module("io.netty:netty-codec:$nettyVersion"))
        substitute(module("io.netty:netty-codec-dns")).using(module("io.netty:netty-codec-dns:$nettyVersion"))
        substitute(module("io.netty:netty-codec-http")).using(module("io.netty:netty-codec-http:$nettyVersion"))
        substitute(module("io.netty:netty-codec-http2")).using(module("io.netty:netty-codec-http2:$nettyVersion"))
        substitute(module("io.netty:netty-codec-socks")).using(module("io.netty:netty-codec-socks:$nettyVersion"))
        substitute(module("io.netty:netty-common")).using(module("io.netty:netty-common:$nettyVersion"))
        substitute(module("io.netty:netty-handler")).using(module("io.netty:netty-handler:$nettyVersion"))
        substitute(module("io.netty:netty-handler-proxy")).using(module("io.netty:netty-handler-proxy:$nettyVersion"))
        substitute(module("io.netty:netty-resolver")).using(module("io.netty:netty-resolver:$nettyVersion"))
        substitute(module("io.netty:netty-resolver-dns")).using(module("io.netty:netty-resolver-dns:$nettyVersion"))
        substitute(
            module("io.netty:netty-resolver-dns-classes-macos"),
        ).using(module("io.netty:netty-resolver-dns-classes-macos:$nettyVersion"))
        substitute(
            module("io.netty:netty-resolver-dns-native-macos"),
        ).using(module("io.netty:netty-resolver-dns-native-macos:$nettyVersion"))
        substitute(module("io.netty:netty-transport")).using(module("io.netty:netty-transport:$nettyVersion"))
        substitute(module("io.netty:netty-transport-classes-epoll")).using(module("io.netty:netty-transport-classes-epoll:$nettyVersion"))
        substitute(module("io.netty:netty-transport-native-epoll")).using(module("io.netty:netty-transport-native-epoll:$nettyVersion"))
        substitute(
            module("io.netty:netty-transport-native-unix-common"),
        ).using(module("io.netty:netty-transport-native-unix-common:$nettyVersion"))
    }
}

tasks.withType<BootJar> {
    archiveFileName.set("ukulele.jar")
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
    doLast {
        // copies the jar into a place where the Dockerfile can find it easily (and users maybe too)
        copy {
            from("build/libs/ukulele.jar")
            into(".")
        }
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
    jvmArgs("-javaagent:${mockitoAgent.asPath}", "-XX:+EnableDynamicAgentLoading", "-Xshare:off")
}

tasks.withType<org.springframework.boot.gradle.tasks.run.BootRun> {
    jvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}
