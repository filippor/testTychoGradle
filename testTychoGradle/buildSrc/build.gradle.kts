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
    (plugins) {
    	"p2-bundle" {
    		id = "p2-bundle"
    				implementationClass = "it.filippor.tycho.BundlePlugin"
    	}
    }
}
dependencies {
    compile(gradleApi())
//    compile(localGroovy())

	compile ("org.eclipse.tycho:org.eclipse.tycho.p2.resolver.shared:1+")
	compile ("org.eclipse.tycho:org.eclipse.tycho.p2.tools.shared:1+")
    compile ("org.eclipse.tycho:org.eclipse.osgi:+")

    compile ("org.eclipse.tycho:org.eclipse.tycho.embedder.shared:1+")
    compile ("org.eclipse.tycho:org.eclipse.tycho.core.shared:1+")
    
    compile ("org.eclipse.platform:org.eclipse.core.runtime:3.13+")
    compile ("org.eclipse.platform:org.eclipse.equinox.common:3.9+")
    compile ("org.eclipse.jdt:org.eclipse.jdt.junit.core:3.9+")
    compile ("org.eclipse.tycho:org.eclipse.osgi.compatibility.state:1.1+")
	compile ("org.eclipse.tycho:org.eclipse.osgi:3.13+")

    compile ("org.eclipse.platform:org.eclipse.equinox.p2.jarprocessor:1.0.500")

//    compile "org.eclipse.tycho:org.eclipse.tycho.p2.resolver.impl:${tychoVersion}"
//    compile "org.eclipse.tycho:org.eclipse.tycho.p2.maven.repository:${tychoVersion}"
//    compile "org.eclipse.tycho:org.eclipse.tycho.p2.tools.impl:${tychoVersion}"
//    compile "org.eclipse.tycho:tycho-bundles-external:${tychoVersion}@zip"
    
    testImplementation ("junit:junit:4.12")

}

