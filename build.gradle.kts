import org.springframework.boot.gradle.tasks.bundling.BootJar

plugins {
    val kotlinVersion = "2.4.0"
    java
    // https://plugins.gradle.org/plugin/org.springframework.boot
    id("org.springframework.boot") version "4.1.0"
    // https://plugins.gradle.org/plugin/org.jetbrains.kotlin.jvm
    kotlin("jvm") version kotlinVersion
    // https://plugins.gradle.org/plugin/org.jetbrains.kotlin.plugin.spring
    kotlin("plugin.spring") version kotlinVersion
    // https://plugins.gradle.org/plugin/org.flywaydb.flyway
    id("org.flywaydb.flyway") version "12.9.0"
    // https://plugins.gradle.org/plugin/com.github.ben-manes.versions
    id("com.github.ben-manes.versions") version "0.54.0"
    id("org.jlleitschuh.gradle.ktlint") version "14.2.0"
}

ktlint {
    version.set("1.8.0")
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
val kotlinVersion = "2.4.0"
val springBootVersion = "4.1.0"
val springFrameworkVersion = "7.0.7"
val jacksonVersion = "3.1.2"
// https://mvnrepository.com/artifact/com.fasterxml.jackson.core/jackson-annotations
val jacksonAnnotationsVersion = "2.21"
val nettyVersion = "4.2.15.Final"
val jdaveVersion = "0.1.8"

kotlin {
    jvmToolchain(25)
}

group = "dev.arbjerg"
version = "2.25.1"

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
    // Align every io.netty:* module to a single version. Spring Boot 4 / reactor-netty pull the
    // newer split modules (netty-codec-base, -http3, -classes-quic, ...) which used to mismatch the
    // modules we pinned by hand, producing a fat jar with both 4.2.12 and 4.2.15 on the classpath.
    // The BOM (enforced) guarantees one version across all modules, including ones added upstream.
    implementation(enforcedPlatform("io.netty:netty-bom:$nettyVersion"))

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
    // https://mvnrepository.com/artifact/org.springframework.boot/spring-boot-starter-actuator
    implementation("org.springframework.boot:spring-boot-starter-actuator:$springBootVersion")
    // https://mvnrepository.com/artifact/org.yaml/snakeyaml
    implementation("org.yaml:snakeyaml:2.6")

    // https://mvnrepository.com/artifact/net.dv8tion/JDA
    // https://github.com/discord-jda/JDA
    implementation("net.dv8tion:JDA:6.4.2")

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
    implementation("com.github.JustinFreitas.lavaplayer:lavaplayer:v2.2.6_17")
    implementation("com.github.JustinFreitas.lavaplayer:lavaplayer-ext-format-xm:v2.2.6_17")
    implementation("com.github.JustinFreitas.lavaplayer:lavaplayer-ext-youtube-rotator:v2.2.6_17")
    // https://mvnrepository.com/artifact/club.minnced/jdave-api
    implementation("club.minnced:jdave-api:$jdaveVersion")
    implementation("club.minnced:jdave-native-win-x86-64:$jdaveVersion")
    implementation("club.minnced:jdave-native-linux-x86-64:$jdaveVersion")
    // https://github.com/lavalink-devs/youtube-source
    // Justin's HttpClient-5 fork of youtube-source, rebuilt against the HC5 lavaplayer fork.
    // The upstream dev.lavalink.youtube:*:1.18.1 jars are compiled against HttpClient 4 and fail at
    // runtime against the HC5 lavaplayer fork with:
    //   AbstractMethodError: ... onContextOpen(org.apache.hc.client5.http.protocol.HttpClientContext)
    // https://github.com/JustinFreitas/youtube-source (tag v1.18.1_1)
    // The "youtube-plugin" module is the standalone Lavalink-server plugin and is unused here.
    implementation("com.github.JustinFreitas.youtube-source:v2:v1.18.1_1")
    implementation("com.github.JustinFreitas.youtube-source:common:v1.18.1_1")
    // https://mvnrepository.com/artifact/commons-io/commons-io
    implementation("commons-io:commons-io:2.21.0")
    // https://mvnrepository.com/artifact/org.json/json
    implementation("org.json:json:20251224")
    // https://mvnrepository.com/artifact/org.jsoup/jsoup
    implementation("org.jsoup:jsoup:1.22.1")

    // https://mvnrepository.com/artifact/com.google.code.gson/gson
    implementation("com.google.code.gson:gson:2.13.2")

    // https://mvnrepository.com/artifact/org.apache.httpcomponents.client5/httpclient5
    implementation("org.apache.httpcomponents.client5:httpclient5:5.6.1")
    // Explicit httpcore5 to access SocketConfig
    implementation("org.apache.httpcomponents.core5:httpcore5:5.4.3")

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
    implementation("org.flywaydb:flyway-core:12.9.0")
    // https://mvnrepository.com/artifact/com.github.ben-manes.caffeine/caffeine
    implementation("com.github.ben-manes.caffeine:caffeine:3.2.4")

    // https://mvnrepository.com/artifact/org.jetbrains.kotlin/kotlin-reflect
    implementation("org.jetbrains.kotlin:kotlin-reflect:$kotlinVersion")
    // https://mvnrepository.com/artifact/org.jetbrains.kotlin/kotlin-stdlib-jdk8
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8:$kotlinVersion")
    // https://mvnrepository.com/artifact/org.jetbrains.kotlinx/kotlinx-coroutines-reactor
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor:1.11.0")
    // https://mvnrepository.com/artifact/io.projectreactor.kotlin/reactor-kotlin-extensions
    implementation("io.projectreactor.kotlin:reactor-kotlin-extensions:1.3.1")
    // https://mvnrepository.com/artifact/org.springframework.boot/spring-boot-starter-test
    testImplementation("org.springframework.boot:spring-boot-starter-test:$springBootVersion") {
        exclude(group = "org.junit.vintage", module = "junit-vintage-engine")
    }

    // https://mvnrepository.com/artifact/org.junit.jupiter/junit-jupiter
    testImplementation("org.junit.jupiter:junit-jupiter:5.14.3")
}

val mockitoAgent: Configuration by configurations.creating

dependencies {
    mockitoAgent("net.bytebuddy:byte-buddy-agent:1.18.10") {
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
