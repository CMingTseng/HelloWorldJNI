plugins {
    application
    alias(libs.plugins.kotlin.jvm)
}

application {
    mainClass.set("org.kmp.jni.HelloWorld")
}

project.logger.lifecycle("[build.gradle.kts] Before apply from jnitask.gradle.kts. Has TEST_JNI_OUTPUT_FOLDER: ${project.extra.has("TEST_JNI_OUTPUT_FOLDER")}")

apply(from = "jnitask.gradle.kts")

project.logger.lifecycle("[build.gradle.kts] After apply from jnitask.gradle.kts. Has TEST_JNI_OUTPUT_FOLDER: ${project.extra.has("TEST_JNI_OUTPUT_FOLDER")}")
val testReadJniOutputFolder = project.extra.get("TEST_JNI_OUTPUT_FOLDER") as String?
project.logger.lifecycle("[build.gradle.kts] Read TEST_JNI_OUTPUT_FOLDER as: '$testReadJniOutputFolder'")

val testReadEffectivePath = project.extra.get("TEST_EFFECTIVE_PATH") as String?
project.logger.lifecycle("[build.gradle.kts] Read TEST_EFFECTIVE_PATH as: '$testReadEffectivePath'")


// Configure tasks
tasks.named("jar", Jar::class.java) {
    dependsOn("buildNativeLib")
    project.logger.lifecycle("[build.gradle.kts] Configuring :jar task. Has TEST_JNI_OUTPUT_FOLDER: ${project.extra.has("TEST_JNI_OUTPUT_FOLDER")}")

    val configuredJniOutputFolder = project.extra.get("TEST_JNI_OUTPUT_FOLDER") as String? // Use the test property name

    if (configuredJniOutputFolder != null && configuredJniOutputFolder.isNotBlank()) {
        val nativeLibsDir = project.file(configuredJniOutputFolder)
        if (nativeLibsDir.exists() && nativeLibsDir.isDirectory) {
            from(nativeLibsDir) {
                project.logger.lifecycle("[JarTask] Adding native libraries from '$nativeLibsDir' to JAR.")
            }
        } else {
            project.logger.warn("[JarTask] TEST_JNI_OUTPUT_FOLDER '$configuredJniOutputFolder' was set, but directory does not exist or is not a directory.")
        }
    } else {
        project.logger.lifecycle("[JarTask] TEST_JNI_OUTPUT_FOLDER was not set (or was empty). No native libs added to JAR.")
    }
}

tasks.withType(Test::class.java).configureEach {
    useJUnitPlatform()
    dependsOn("buildNativeLib")
    project.logger.lifecycle("[build.gradle.kts] Configuring Test task. Has TEST_EFFECTIVE_PATH: ${project.extra.has("TEST_EFFECTIVE_PATH")}")
    val finalLibsPathForTest = project.extra.get("TEST_EFFECTIVE_PATH") as String? // Can be null if not found
    if (finalLibsPathForTest == null) {
        project.logger.error("[TestTask] CRITICAL: TEST_EFFECTIVE_PATH not found in project.extra!")
        // Potentially throw an exception or set a dummy path to avoid further errors in test config
        throw GradleException("TEST_EFFECTIVE_PATH extra property not found during Test task configuration.")
    }
    systemProperty("java.library.path", finalLibsPathForTest)
    doFirst {
        project.logger.lifecycle("[TestTask] Running tests with java.library.path='$finalLibsPathForTest'")
    }
}

tasks.withType(JavaExec::class.java).configureEach {
    if (name == "run") {
        dependsOn("buildNativeLib")
        project.logger.lifecycle("[build.gradle.kts] Configuring Run task. Has TEST_EFFECTIVE_PATH: ${project.extra.has("TEST_EFFECTIVE_PATH")}")
        val finalLibsPathForRun = project.extra.get("TEST_EFFECTIVE_PATH") as String?
        if (finalLibsPathForRun == null) {
            project.logger.error("[RunTask] CRITICAL: TEST_EFFECTIVE_PATH not found in project.extra!")
            throw GradleException("TEST_EFFECTIVE_PATH extra property not found during Run task configuration.")
        }
        jvmArgs("-Djava.library.path=${finalLibsPathForRun}")
        doFirst {
            project.logger.lifecycle("[RunTask] Configuring 'run' task with java.library.path='$finalLibsPathForRun'")
        }
    }
}

dependencies {
    testImplementation(kotlin("test"))
}
