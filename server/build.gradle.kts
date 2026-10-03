plugins {
    kotlin("jvm") version "2.4.20"
    kotlin("plugin.serialization") version "2.4.20"
    application
}

group = "io.github.devasenan134"
version = "0.1.0"

repositories {
    mavenCentral()
}

val ktor = "3.6.0"

dependencies {
    implementation("io.ktor:ktor-server-core:$ktor")
    implementation("io.ktor:ktor-server-netty:$ktor")
    implementation("io.ktor:ktor-server-content-negotiation:$ktor")
    implementation("io.ktor:ktor-server-auth:$ktor")
    implementation("io.ktor:ktor-server-websockets:$ktor")
    implementation("io.ktor:ktor-server-status-pages:$ktor")
    implementation("io.ktor:ktor-server-call-logging:$ktor")
    implementation("io.ktor:ktor-server-partial-content:$ktor")
    implementation("io.ktor:ktor-server-compression:$ktor")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktor")
    implementation("io.ktor:ktor-client-core:$ktor")
    implementation("io.ktor:ktor-client-cio:$ktor")
    implementation("io.ktor:ktor-client-content-negotiation:$ktor")
    implementation("org.xerial:sqlite-jdbc:3.53.4.0")
    implementation("ch.qos.logback:logback-classic:1.6.3")
    implementation("com.google.auth:google-auth-library-oauth2-http:1.52.0") // Firebase login for push

    testImplementation(kotlin("test"))
    testImplementation("io.ktor:ktor-server-test-host:$ktor")
    testImplementation("io.ktor:ktor-client-websockets:$ktor")
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("io.github.devasenan134.jukebox.server.MainKt")
}

tasks.test {
    useJUnitPlatform()
}

// Local testing: the server plus pretend "bot_" users (see src/test/.../DevServer.kt).
tasks.register<JavaExec>("runDev") {
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("io.github.devasenan134.jukebox.server.DevServerKt")
    // Your Navidrome server: -PnavidromeUrl=https://... or JUKEBOX_SERVER_URL in ~/.gradle/gradle.properties.
    environment("NAVIDROME_URL", (project.findProperty("navidromeUrl") ?: project.findProperty("JUKEBOX_SERVER_URL") ?: "http://localhost:4533") as String)
    environment("DB_PATH", layout.buildDirectory.file("dev/jukebox.db").get().asFile.path)
    // Optional: real push notifications while testing (-PfirebaseKey=/path/to/key.json).
    (project.findProperty("firebaseKey") as String?)?.let { environment("FIREBASE_KEY_FILE", it) }
    // Optional: a (copy of a) Navidrome database, for mixes and admin stats (-PnavidromeDb=/path/to/navidrome.db).
    (project.findProperty("navidromeDb") as String?)?.let { environment("NAVIDROME_DB", it) }
}

// Prints the mixes a person would get, from a copy of Navidrome's database and the analyzer's features.db:
// ./gradlew previewMixes -PnavidromeDb=navidrome.db -PfeaturesDb=features.db -Puser=<navidrome username>
tasks.register<JavaExec>("previewMixes") {
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("io.github.devasenan134.jukebox.server.MixPreviewKt")
    args(
        project.findProperty("navidromeDb") ?: "navidrome.db",
        project.findProperty("featuresDb") ?: "",
        project.findProperty("user") ?: "",
    )
}

// Try search on a copy of the real library:
// ./gradlew previewSearch -PnavidromeDb=navidrome.db -PcastFile=movie-cast.jsonl -Pqueries="kanave|vairamuthu|vijay"
tasks.register<JavaExec>("previewSearch") {
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("io.github.devasenan134.jukebox.server.SearchPreviewKt")
    args(
        listOf(project.findProperty("navidromeDb") ?: "navidrome.db", project.findProperty("castFile") ?: "movie-cast.jsonl") +
            (project.findProperty("queries") as String? ?: "").split("|").filter { it.isNotBlank() },
    )
}
