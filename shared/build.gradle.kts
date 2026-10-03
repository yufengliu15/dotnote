plugins { id("org.jetbrains.kotlin.multiplatform") }

kotlin {
    jvm { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
    iosArm64()
    iosSimulatorArm64()
    iosX64()
    targets.withType<org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget>().configureEach {
        binaries.framework {
            baseName = "DotnoteCore"
            isStatic = true
        }
    }
    sourceSets.commonTest.dependencies { implementation(kotlin("test")) }
}
