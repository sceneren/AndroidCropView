plugins {
    alias(libs.plugins.android.library)
    `maven-publish`
}

android {
    namespace = "com.github.sceneren.cropview"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        minSdk = 24
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

dependencies {
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])

                groupId = jitpackGroupId()
                artifactId = providers.gradleProperty("POM_ARTIFACT_ID").orElse(project.name).get()
                version = System.getenv("VERSION")
                    ?: providers.gradleProperty("VERSION_NAME").orElse("1.0.0").get()

                pom {
                    name.set("AndroidCropView")
                    description.set("A custom Android image crop view with rotation, zoom, fling, and custom crop shapes.")
                    url.set("https://github.com/sceneren/AndroidCropView")
                    licenses {
                        license {
                            name.set("The Apache License, Version 2.0")
                            url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                        }
                    }
                    developers {
                        developer {
                            id.set("sceneren")
                            name.set("sceneren")
                        }
                    }
                    scm {
                        connection.set("scm:git:git://github.com/sceneren/AndroidCropView.git")
                        developerConnection.set("scm:git:ssh://github.com/sceneren/AndroidCropView.git")
                        url.set("https://github.com/sceneren/AndroidCropView")
                    }
                }
            }
        }
    }
}

fun jitpackGroupId(): String {
    val explicitGroup = providers.gradleProperty("POM_GROUP_ID").orNull
    if (!explicitGroup.isNullOrBlank()) return explicitGroup

    val jitpackGroup = System.getenv("GROUP")
    val jitpackArtifact = System.getenv("ARTIFACT")
    if (!jitpackGroup.isNullOrBlank() && !jitpackArtifact.isNullOrBlank()) {
        return "$jitpackGroup.$jitpackArtifact"
    }

    return "com.github.sceneren.AndroidCropView"
}
