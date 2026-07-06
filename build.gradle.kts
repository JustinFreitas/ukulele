import org.springframework.boot.gradle.tasks.bundling.BootJar

plugins {
    java
    alias(libs.plugins.springboot)
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.flyway)
    alias(libs.plugins.benmanes)
    alias(libs.plugins.ktlint)
}

ktlint {
    version.set(libs.versions.ktlintEngine)
}

tasks.withType<com.github.benmanes.gradle.versions.updates.DependencyUpdatesTask> {
    rejectVersionIf {
        val stableKeyword = listOf("RELEASE", "FINAL", "GA").any { candidate.version.uppercase().contains(it) }
        val regex = "^[0-9,.v-]+(-r)?$".toRegex()
        val isStable = stableKeyword || regex.matches(candidate.version)
        !isStable
    }
    outputFormatter = "json,plain"
}

kotlin {
    jvmToolchain(25)
}

group = "dev.arbjerg"
version = "2.25.3"

repositories {
    mavenLocal()
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

if (System.getenv("CI") == null) {
    configurations.all {
        resolutionStrategy.dependencySubstitution {
            substitute(
                module("com.github.JustinFreitas.lavaplayer:lavaplayer"),
            ).using(module("com.github.justinfreitas:lavaplayer:v2.2.6_27"))
            substitute(
                module("com.github.JustinFreitas.lavaplayer:lavaplayer-ext-format-xm"),
            ).using(module("com.github.justinfreitas:lavaplayer-ext-format-xm:v2.2.6_27"))
            substitute(
                module("com.github.JustinFreitas.lavaplayer:lavaplayer-ext-youtube-rotator"),
            ).using(module("com.github.justinfreitas:lavaplayer-ext-youtube-rotator:v2.2.6_27"))
            substitute(module("com.github.JustinFreitas.youtube-source:v2")).using(module("dev.lavalink.youtube:v2:v1.18.1_9"))
            substitute(module("com.github.JustinFreitas.youtube-source:common")).using(module("dev.lavalink.youtube:common:v1.18.1_9"))
        }
    }
}

dependencies {
    // Align every io.netty:* module to a single version. Spring Boot 4 / reactor-netty pull the
    // newer split modules (netty-codec-base, -http3, -classes-quic, ...) which used to mismatch the
    // modules we pinned by hand, producing a fat jar with both 4.2.12 and 4.2.15 on the classpath.
    // The BOM (enforced) guarantees one version across all modules, including ones added upstream.
    implementation(enforcedPlatform(libs.netty.bom))

    // https://mvnrepository.com/artifact/org.springframework.boot/spring-boot-configuration-processor
    implementation(libs.springboot.configuration.processor)
    // https://mvnrepository.com/artifact/org.springframework.boot/spring-boot-starter-data-r2dbc
    implementation(libs.springboot.starter.data.r2dbc)
    // https://mvnrepository.com/artifact/org.springframework.boot/spring-boot-starter-webflux
    implementation(libs.springboot.starter.webflux)
    // https://mvnrepository.com/artifact/org.springframework.boot/spring-boot-starter-web
    implementation(libs.springboot.starter.web)
    // https://mvnrepository.com/artifact/org.springframework.boot/spring-boot-starter-websocket
    implementation(libs.springboot.starter.websocket)
    // https://mvnrepository.com/artifact/org.springframework.boot/spring-boot-starter-actuator
    implementation(libs.springboot.starter.actuator)
    // https://mvnrepository.com/artifact/org.yaml/snakeyaml
    implementation(libs.snakeyaml)

    // https://mvnrepository.com/artifact/net.dv8tion/JDA
    // https://github.com/discord-jda/JDA
    implementation(libs.jda)

    // Jocull has the latest Ukulele we should pattern after in JDA-5 branch.
    // https://github.com/jocull/ukulele/tree/jda-5
    // https://github.com/lavalink-devs/lavaplayer
    // implementation("dev.arbjerg:lavaplayer:2.2.6")
    // Above broken for YouTube links.  Use Justin's fork.
    // https://github.com/JustinFreitas/lavaplayer
    // Depend on the concrete submodules, not the jitpack aggregator
    // (com.github.JustinFreitas:lavaplayer). The aggregator's own artifact is an
    // empty manifest-only jar that collides with the real submodule jar by filename
    // in BOOT-INF/lib, so the bootJar classloader binds to the empty copy and fails
    // with ClassNotFoundException for AudioPlayerManager.
    implementation(libs.lavaplayer)
    implementation(libs.lavaplayer.ext.format.xm)
    implementation(libs.lavaplayer.ext.youtube.rotator)
    // https://mvnrepository.com/artifact/club.minnced/jdave-api
    implementation(libs.jdave.api)
    implementation(libs.jdave.native.win)
    implementation(libs.jdave.native.linux)
    // https://github.com/lavalink-devs/youtube-source
    // Justin's HttpClient-5 fork of youtube-source, rebuilt against the HC5 lavaplayer fork.
    // The upstream dev.lavalink.youtube:*:1.18.1 jars are compiled against HttpClient 4 and fail at
    // runtime against the HC5 lavaplayer fork with:
    //   AbstractMethodError: ... onContextOpen(org.apache.hc.client5.http.protocol.HttpClientContext)
    // https://github.com/JustinFreitas/youtube-source (tag v1.18.1_1)
    // The "youtube-plugin" module is the standalone Lavalink-server plugin and is unused here.
    implementation(libs.youtube.source.v2)
    implementation(libs.youtube.source.common)
    // https://mvnrepository.com/artifact/commons-io/commons-io
    implementation(libs.commons.io)
    // https://mvnrepository.com/artifact/org.json/json
    implementation(libs.org.json)
    // https://mvnrepository.com/artifact/org.jsoup/jsoup
    implementation(libs.jsoup)

    // https://mvnrepository.com/artifact/com.google.code.gson/gson
    implementation(libs.gson)

    // https://mvnrepository.com/artifact/org.apache.httpcomponents.client5/httpclient5
    implementation(libs.httpclient5)
    // Explicit httpcore5 to access SocketConfig
    implementation(libs.httpcore5)

    // https://mvnrepository.com/artifact/com.fasterxml.jackson.core/jackson-core
    implementation(libs.jackson.core)
    // https://mvnrepository.com/artifact/com.fasterxml.jackson.core/jackson-databind
    implementation(libs.jackson.databind)
    // https://mvnrepository.com/artifact/com.fasterxml.jackson.core/jackson-annotations
    implementation(libs.jackson.annotations)

    // https://mvnrepository.com/artifact/com.h2database/h2
    // NOTE: 2.4.240 caused a problem where Ukulele couldn't join the channel for some reason.
    // UPDATE: 20251213 - I think since both of these deps are updated now (2.4.240 and 1.1.0), we can try the DB update
    // again and see if we can get everything to work now.
    runtimeOnly(libs.h2)
    // https://mvnrepository.com/artifact/io.r2dbc/r2dbc-h2
    implementation(libs.r2dbc.h2) {
        exclude(group = "com.h2database", module = "h2")
    }
    // https://mvnrepository.com/artifact/org.flywaydb/flyway-core
    implementation(libs.flyway.core)
    // https://mvnrepository.com/artifact/com.github.ben-manes.caffeine/caffeine
    implementation(libs.caffeine)

    // https://mvnrepository.com/artifact/org.jetbrains.kotlin/kotlin-reflect
    implementation(libs.kotlin.reflect)
    // https://mvnrepository.com/artifact/org.jetbrains.kotlin/kotlin-stdlib-jdk8
    implementation(libs.kotlin.stdlib.jdk8)
    // https://mvnrepository.com/artifact/org.jetbrains.kotlinx/kotlinx-coroutines-reactor
    implementation(libs.kotlinx.coroutines.reactor)
    // https://mvnrepository.com/artifact/io.projectreactor.kotlin/reactor-kotlin-extensions
    implementation(libs.reactor.kotlin.extensions)
    // https://mvnrepository.com/artifact/org.springframework.boot/spring-boot-starter-test
    testImplementation(libs.springboot.starter.test) {
        exclude(group = "org.junit.vintage", module = "junit-vintage-engine")
    }

    // https://mvnrepository.com/artifact/org.junit.jupiter/junit-jupiter
    testImplementation(libs.junit.jupiter)
}

val mockitoAgent: Configuration by configurations.creating

dependencies {
    mockitoAgent(libs.byte.buddy.agent) {
        isTransitive = false
    }
}

// Dependency versions are managed by the Spring Boot 4 BOM (applied by the Spring Boot Gradle
// plugin) plus the enforced io.netty:netty-bom platform declared in the dependencies block above.
// The previous ~31-entry hand-pinned resolutionStrategy.dependencySubstitution block was removed:
// a runtimeClasspath diff (with vs without it) showed it was either a no-op or merely held
// BOM-managed deps *below* their BOM versions — the same brittleness that produced the earlier
// Netty skew. App libraries not covered by the BOM (gson, org.json, jsoup, rhino via lavaplayer,
// etc.) keep their versions via their direct dependency declarations above.

tasks.withType<BootJar> {
    archiveFileName.set("ukulele.jar")
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
    classpath(tasks.jar.get().archiveFile)
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
