plugins {
    java
}

group = "com.mosaicgem"
version = "1.1.2"
description = "MosaicGem - Folia 26.x (26.1+) server plugin"

repositories {
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    // 针对 26.x 最低 stable 编译，保证兼容全部 26.x（26.1.2 -> 26.2.x）
    compileOnly("dev.folia:folia-api:26.1.2.build.8-stable")
    compileOnly("net.kyori:adventure-text-minimessage:5.2.0")
    testImplementation("dev.folia:folia-api:26.1.2.build.8-stable")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    // 使用当前 JDK 编译，但目标字节码保持 Java 25 兼容
    options.release = 25
}

tasks.processResources {
    filesMatching("plugin.yml") {
        expand("projectVersion" to project.version)
    }
}

tasks.jar {
    archiveBaseName.set("MosaicGem")
}

tasks.test {
    useJUnitPlatform()
}
