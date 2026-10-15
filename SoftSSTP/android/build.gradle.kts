allprojects {
    repositories {
        google()
        mavenCentral()
    }
}

val newBuildDir: Directory =
    rootProject.layout.buildDirectory
        .dir("../../build")
        .get()
rootProject.layout.buildDirectory.value(newBuildDir)

subprojects {
    val newSubprojectBuildDir: Directory = newBuildDir.dir(project.name)
    project.layout.buildDirectory.value(newSubprojectBuildDir)
}

// The linker build-id hashes the debug info, which records the NDK path, so the
// id changes as soon as the build tree lives somewhere else. It survives
// stripping and ships inside the APK, which makes two builds of the same source
// differ. Drop it for every native module in the build, not just the app's own:
// package:jni builds libdartjni.so from its own CMakeLists, which this
// repository does not own. Passing the flag through CMake rather than through
// the environment keeps it in one place instead of two build configurations that
// can drift apart.
subprojects {
    plugins.withId("com.android.library") {
        extensions.configure<com.android.build.api.dsl.LibraryExtension>("android") {
            defaultConfig {
                externalNativeBuild {
                    cmake {
                        arguments.add("-DCMAKE_SHARED_LINKER_FLAGS=-Wl,--build-id=none")
                    }
                }
            }
        }
    }
}
subprojects {
    project.evaluationDependsOn(":app")
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
