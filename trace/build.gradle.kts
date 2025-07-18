import org.gradle.kotlin.dsl.named
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("maven-publish")
    id("org.jetbrains.dokka")
}

repositories {
    mavenCentral()
}

sourceSets {
    main {
        java.srcDirs("src/main")
    }

    test {
        java.srcDir("src/test")
    }

    dependencies {
        implementation(project(":common"))

        testImplementation(project(":common"))
    }
}

tasks {
    named<JavaCompile>("compileTestJava") {
        setupJavaToolchain(project)
    }
    named<KotlinCompile>("compileTestKotlin") {
        setupKotlinToolchain(project)
    }

    withType<KotlinCompile> {
        getAccessToInternalDefinitionsOf(project(":common"))
    }
}

val jar = tasks.jar {
    archiveFileName.set("trace.jar")
}

val sourcesJar = tasks.register<Jar>("sourcesJar") {
    from(sourceSets["main"].allSource)
    archiveClassifier.set("sources")
}

val javadocJar = createJavadocJar()

publishing {
    publications {
        register("maven", MavenPublication::class) {
            val groupId: String by project
            val traceArtifactId: String by project
            val traceVersion: String by project

            this.groupId = groupId
            this.artifactId = traceArtifactId
            this.version = traceVersion

            from(components["kotlin"])
            artifact(sourcesJar)
            artifact(javadocJar)

            configureMavenPublication {
                name.set(traceArtifactId)
                description.set("Lincheck trace model and (de)serialization library")
            }
        }
    }

    configureRepositories(
        artifactsRepositoryUrl = rootProject.run { uri(layout.buildDirectory.dir("artifacts/maven")) }
    )
}

configureSigning()