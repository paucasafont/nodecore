// VeriBlock Blockchain Project
// Copyright 2017-2018 VeriBlock, Inc
// Copyright 2018-2020 Xenios SEZC
// All rights reserved.
// https://www.veriblock.org
// Distributed under the MIT software license, see the accompanying
// file LICENSE or http://www.opensource.org/licenses/mit-license.php.
import com.google.protobuf.gradle.proto
import com.google.protobuf.gradle.protobuf
import com.google.protobuf.gradle.protoc
import groovy.util.Eval
import org.gradle.api.internal.plugins.WindowsStartScriptGenerator
import org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL

plugins {
    java
    kotlin("jvm")
    idea
    application
    id("com.google.protobuf")
}

configurations.all {
    // check for updates every build for changing modules
    resolutionStrategy.cacheChangingModulesFor(0, "seconds")
}

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8:$kotlinVersion")
    implementation("org.jetbrains.kotlin:kotlin-reflect:$kotlinVersion")

    runtimeOnly("org.codehaus.groovy:groovy:2.4.12")

    implementation(project(":veriblock-core"))
    implementation(project(":veriblock-shell"))
    implementation(project(":nodecore-grpc"))
    implementation(project(":altchain-sdk"))
    implementation(project(":pop-miners:pop-miners-common"))
    runtimeOnly(project(":altchain-plugins"))

    implementation("com.github.veriblock.alt-integration:mock-pop-mining:0.0.7")

    // Dependency Injection
    implementation("org.koin:koin-core:$koinVersion")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutinesVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-jdk8:$coroutinesVersion")

    // HTTP API
    implementation("io.ktor:ktor-server-netty:$ktorVersion")
    implementation("io.ktor:ktor-locations:$ktorVersion")
    implementation("io.ktor:ktor-gson:$ktorVersion")
    // Swagger integration, expecting an official integration to be released soon
    implementation("com.github.nielsfalk:ktor-swagger:0.5.0")

    // Logging
    implementation("io.github.microutils:kotlin-logging:1.6.26")
    implementation("ch.qos.logback:logback-classic:1.2.3")

    implementation("commons-cli:commons-cli:1.4")
    implementation("com.google.code.gson:gson:2.8.2")
    implementation("com.google.guava:guava:26.0-jre")
    implementation("com.diogonunes:JCDP:2.0.3.1")
    implementation("com.j256.ormlite:ormlite-core:5.1")
    implementation("com.j256.ormlite:ormlite-jdbc:5.1")
    implementation("org.xerial:sqlite-jdbc:3.23.1")

    testImplementation("junit:junit:4.12")
    testImplementation("org.apache.commons:commons-lang3:3.8.1")
    testImplementation("io.kotlintest:kotlintest-assertions:3.4.1")
}

protobuf {
    generatedFilesBaseDir = "$projectDir/src/generated"

    protoc {
        artifact = "com.google.protobuf:protoc:3.2.0"
    }
}

sourceSets {
    main {
        proto {}
        java {
            srcDir("$projectDir/src/generated/main/java")
        }
    }
}

tasks.test {
    testLogging {
        exceptionFormat = FULL
    }
}

tasks.named<JavaExec>("run") {
    standardInput = System.`in`
    standardOutput = System.out

    if (project.hasProperty("appArgs")) {
        args = Eval.me(properties["appArgs"] as String) as List<String>
    }
}

application.applicationName = "veriblock-alt-pop"
application.mainClassName = "org.veriblock.miners.pop.ProgramKt"

setupJar("VeriBlock Proof-of-Proof (PoP) Miner", "veriblock.miners.pop")

tasks.distZip {
    archiveFileName.set("${application.applicationName}-${prettyVersion()}.zip")
}
tasks.distTar {
    archiveFileName.set("${application.applicationName}-${prettyVersion()}.tar")
}

tasks.startScripts {
    (windowsStartScriptGenerator as WindowsStartScriptGenerator).template =
        resources.text.fromFile("windowsStartScript.txt")
}

setupJacoco()