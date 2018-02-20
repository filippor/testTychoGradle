plugins {
//	groovy
	eclipse
	`java-gradle-plugin`
    `kotlin-dsl`
}


repositories {
	/*maven(
		url="https://repo.eclipse.org/content/repositories/tycho-snapshots/"
	)*/
    mavenCentral()
    jcenter()
}
gradlePlugin {
    (plugins) {
        "gradle-tycho" {
            id = "gradle-tycho"
            implementationClass = "it.filippor.tycho.GradleTycho"
        }
    }
}
dependencies {
    compile(gradleApi())
//    compile(localGroovy())

	compile ("org.eclipse.tycho:org.eclipse.tycho.p2.resolver.shared:1+")
    compile ("org.eclipse.tycho:org.eclipse.osgi:+")

    compile ("org.eclipse.tycho:org.eclipse.tycho.embedder.shared:1+")
    compile ("org.eclipse.tycho:org.eclipse.tycho.core.shared:1+")
    
    
//    compile "org.eclipse.tycho:org.eclipse.tycho.p2.resolver.impl:${tychoVersion}"
//    compile "org.eclipse.tycho:org.eclipse.tycho.p2.maven.repository:${tychoVersion}"
//    compile "org.eclipse.tycho:org.eclipse.tycho.p2.tools.impl:${tychoVersion}"
//    compile "org.eclipse.tycho:tycho-bundles-external:${tychoVersion}@zip"
    
    testImplementation ("junit:junit:4.12")

}

