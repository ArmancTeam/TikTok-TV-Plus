pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

// Termux's stock aapt2 cannot run AGP's prebuilt binary. Apply the override
// only when that binary is actually present so CI and desktop builds work.
val termuxAapt2 = file("/data/data/com.termux/files/usr/bin/aapt2")
if (termuxAapt2.exists()) {
    gradle.beforeProject {
        extra.set("android.aapt2FromMavenOverride", termuxAapt2.absolutePath)
    }
}

rootProject.name = "TikTokFix"
include(":app")