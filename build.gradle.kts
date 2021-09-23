defaultTasks("mvgCorrector", "test", "publish", "mvgTagger")

plugins {
    `java-library`
    `maven-publish`
    id("org.modelingvalue.gradle.mvgplugin") version "0.4.26"
}
dependencies {
    
    
    //testImplementation("org.junit.jupiter:junit-jupiter-api:5.6.2")
    //testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine")

}
publishing {
    publications {
        create<MavenPublication>("sync-proxy") {
            from(components["java"])
        }
    }
}
