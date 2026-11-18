pluginManagement {
    includeBuild("build-logic")
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

rootProject.name = "build-it"

include(":app")
include(":core:common")
include(":core:model")
include(":core:designsystem")
include(":core:database")
include(":core:data")
include(":engine:project")
include(":engine:layout")
include(":engine:blocks")
include(":engine:editor")
include(":feature:home")
include(":feature:projects")
include(":feature:workspace")
include(":feature:designer")
include(":feature:blocks")
include(":feature:code")
include(":feature:settings")
