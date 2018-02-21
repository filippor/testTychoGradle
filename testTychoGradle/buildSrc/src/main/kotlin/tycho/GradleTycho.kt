package it.filippor.tycho

import it.filippor.tycho.library.EquinoxEmbedder
import it.filippor.tycho.library.MavenGradleLoggerAdapter
import org.eclipse.tycho.core.ee.shared.ExecutionEnvironmentConfigurationStub
import org.eclipse.tycho.core.resolver.shared.MavenRepositoryLocation
import org.eclipse.tycho.core.shared.MavenLogger
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.invoke
import java.io.File
import java.net.URI
import org.gradle.kotlin.dsl.*

/**
 *  Extension class providing top-level content of the DSL definition for the plug-in.
 */
open class EquinoxEmbedderDesc(
		var tychoVersion: String = "1.1.0",
		var extraSystemPackage: List<String> = mutableListOf(
				"org.eclipse.tycho",
				"org.eclipse.tycho.artifacts",
				"org.eclipse.tycho.core.ee.shared",
				"org.eclipse.tycho.core.shared",
				"org.eclipse.tycho.core.resolver.shared",
				"org.eclipse.tycho.locking.facade",
				"org.eclipse.tycho.p2.metadata",
				"org.eclipse.tycho.p2.repository",
				"org.eclipse.tycho.p2.resolver.facade",
				"org.eclipse.tycho.p2.target.facade",
				"org.eclipse.tycho.p2.tools",
				"org.eclipse.tycho.p2.tools.director.shared",
				"org.eclipse.tycho.p2.tools.publisher.facade",
				"org.eclipse.tycho.p2.tools.mirroring.facade",
				"org.eclipse.tycho.p2.tools.verifier.facade",
				"org.eclipse.tycho.repository.registry.facade",
				"org.eclipse.tycho.p2.tools.baseline.facade"
		),
		var p2repo: MavenRepositoryLocation = MavenRepositoryLocation("Oxygen", URI.create("http://download.eclipse.org/releases/oxygen")),
		var executionEnviroment: ExecutionEnvironmentConfigurationStub = ExecutionEnvironmentConfigurationStub("JavaSE-1.8"),
		internal var embedder: EquinoxEmbedder? = null,
		internal var logger: MavenLogger? = null
) {
	fun repo(name: String, url: String) {
		p2repo = MavenRepositoryLocation(name, URI.create(url));
	}
}



class GradleTycho : Plugin<Project> {
	companion object {
		val TYCHO_INSTALL_ZIP = "tychoInstall"
		val TYCHO_BUNDLES = "tychoBundles"

	}


	override fun apply(project: Project) {
		configureProject(project)
		project.afterEvaluate {
			createEquinoxEmbedder(project)
		}
	}

	fun configureProject(project: Project) {
		with(project) {
			extensions.create("equinoxEmbedder", EquinoxEmbedderDesc::class.java)
			

			var eqDesc = extensions["equinoxEmbedder"] as EquinoxEmbedderDesc

			configurations {
				create(TYCHO_INSTALL_ZIP)
				create(TYCHO_BUNDLES)
			}
			dependencies {
				TYCHO_INSTALL_ZIP("org.eclipse.tycho:tycho-bundles-external:${eqDesc.tychoVersion}@zip")
				TYCHO_BUNDLES("org.eclipse.tycho:org.eclipse.tycho.p2.resolver.impl:${eqDesc.tychoVersion}")
				TYCHO_BUNDLES("org.eclipse.tycho:org.eclipse.tycho.p2.maven.repository:${eqDesc.tychoVersion}")
				TYCHO_BUNDLES("org.eclipse.tycho:org.eclipse.tycho.p2.tools.impl:${eqDesc.tychoVersion}")
			}
		}
	}


	fun createEquinoxEmbedder(project: Project) {
		project.logger.info("create equinox embedder")
		var sdkArchive = project.configurations.getByName(TYCHO_INSTALL_ZIP).getSingleFile()

		//unzip(src: sdkArchive, dest: sdkArchive. parentFile, overwrite: true)
		project.ant.invokeMethod("unzip", mapOf(
				"src" to sdkArchive,
				"dest" to sdkArchive.parentFile,
				"overwrite" to true
		))
		var installLocation = File(sdkArchive.parentFile, "eclipse");

		with(project.extensions.getByType(EquinoxEmbedderDesc::class.java)) {
			var la = MavenGradleLoggerAdapter(project.logger)
			logger = la
			embedder = EquinoxEmbedder(
					installLocation,
					project.configurations.getByName(TYCHO_BUNDLES).getFiles(),
					project.extensions.getByType(EquinoxEmbedderDesc::class.java).extraSystemPackage,
					File(project.buildDir, "p2"),
					false,
					la
			)
		}

	}

	


}