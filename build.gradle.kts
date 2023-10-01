plugins {
    `java-library`
    `maven-publish`
    id("io.freefair.lombok") version "8.3"
}

repositories {
    mavenLocal()
    maven {
        url = uri("https://jitpack.io")
    }

    maven {
        url = uri("https://repo.maven.apache.org/maven2/")
    }
}

dependencies {
    api("eu.darkbot.DarkBotAPI:darkbot-common:0.8.2")
    api("org.jetbrains:annotations:24.0.1")
}

group = "eu.darkbot.avyiel"
version = "1.0.0"
description = "AvysPlugin"
java.sourceCompatibility = JavaVersion.VERSION_1_8

publishing {
    publications.create<MavenPublication>("maven") {
        from(components["java"])
    }
}

tasks.withType<JavaCompile>() {
    options.encoding = "UTF-8"
}

tasks.withType<Javadoc>() {
    options.encoding = "UTF-8"
}
