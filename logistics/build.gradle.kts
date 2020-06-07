// Copyright 2020 The Terasology Foundation
// SPDX-License-Identifier: Apache-2.0

import java.net.URI

plugins {
    kotlin("jvm") version "1.3.72"
    application
}

repositories {
    mavenCentral()
    jcenter()

    maven {
        name = "Terasology Artifactory"
        url = URI("http://artifactory.terasology.org/artifactory/virtual-repo-live")
        @Suppress("UnstableApiUsage")
        isAllowInsecureProtocol = true  // 😱
    }
}

dependencies {
    implementation(kotlin("stdlib"))
    implementation(kotlin("stdlib-jdk8"))

    implementation("org.terasology:gestalt-module:5.1.5") {
        // TODO: sync version w engine?
        because("inspecting terasology modules")
    }

    implementation("com.github.ajalt:clikt:2.7.1") {
        because("command line parsing")
    }

    implementation("org.eclipse.jgit:org.eclipse.jgit:5.+") {
        because("git interface")  // gradle distro 6.4 includes jgit 5.5
    }

    runtimeOnly("org.slf4j:slf4j-simple:1.7.+") {
        because("jgit uses slf4j logging")
    }
}


application {
    applicationName = "gooky"
    mainClassName = "org.terasology.logistics.gooky.GookyKt"
}
