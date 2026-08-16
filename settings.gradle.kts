rootProject.name = "keep"

// Standalone builds resolve jfk, ack, and kviash from JitPack (see build.gradle.kts).
// Full-source development can substitute sibling checkouts for those JitPack modules.
val useLocalProjects =
    providers.gradleProperty("keep.useLocalProjects").orNull?.toBooleanStrictOrNull()
        ?: System.getenv("KEEP_USE_LOCAL_PROJECTS")?.toBooleanStrictOrNull()
        ?: false

if (useLocalProjects) {
    mapOf(
        "ack" to "com.github.TekFive:ack",
        "jfk" to "com.github.TekFive:jfk",
        "kviash" to "com.github.TekFive:kviash",
    ).forEach { (projectName, moduleCoordinates) ->
        includeBuild("../$projectName") {
            dependencySubstitution {
                substitute(module(moduleCoordinates)).using(project(":"))
            }
        }
    }
}
