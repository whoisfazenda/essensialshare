import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose.multiplatform)
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(project(":core"))
    implementation(compose.desktop.currentOs)
    implementation(compose.foundation)
    implementation(compose.ui)
    implementation(libs.coroutines.swing)
    testImplementation(kotlin("test-junit5"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> { useJUnitPlatform() }

compose.desktop {
    application {
        mainClass = "dev.essentialshare.desktop.MainKt"
        jvmArgs += listOf("-Xmx512m", "-Dfile.encoding=UTF-8")
        nativeDistributions {
            targetFormats(TargetFormat.Exe)
            packageName = "Essential Share"
            packageVersion = "1.0.0"
            description = "Fast local file sharing between your phone and PC"
            vendor = "Essential Share"
            // X25519 lives in jdk.crypto.ec, which a trimmed runtime would drop
            modules("jdk.crypto.ec", "java.naming", "jdk.unsupported", "java.net.http")
            windows {
                menuGroup = "Essential Share"
                shortcut = true
                if (file("icon.ico").exists()) iconFile.set(file("icon.ico"))
            }
        }
    }
}

// Headless renderer used to check the design without opening a window
tasks.register<JavaExec>("renderPreview") {
    group = "verification"
    mainClass.set("dev.essentialshare.desktop.PreviewKt")
    classpath = sourceSets["main"].runtimeClasspath
    args(layout.buildDirectory.dir("preview").get().asFile.absolutePath)
}

// Promo cards for the store page / community post, drawn with the real app UI
tasks.register<JavaExec>("renderPromo") {
    group = "verification"
    mainClass.set("dev.essentialshare.desktop.PromoKt")
    classpath = sourceSets["main"].runtimeClasspath
    args(rootProject.layout.projectDirectory.dir("promo").asFile.absolutePath)
}
