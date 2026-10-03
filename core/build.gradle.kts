plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin { jvmToolchain(17) }

dependencies {
    api(libs.coroutines.core)
    testImplementation(kotlin("test-junit5"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test { useJUnitPlatform() }

tasks.register<JavaExec>("fakePhone") {
    group = "verification"
    mainClass.set("dev.essentialshare.core.FakePhoneKt")
    classpath = sourceSets["test"].runtimeClasspath
}
