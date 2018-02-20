package it.filippor.tycho

import it.filippor.tycho.library.EquinoxEmbedder
import it.filippor.tycho.library.MavenGradleLoggerAdapter
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.*
import org.gradle.kotlin.dsl.*
import java.net.URI

import org.eclipse.tycho.ArtifactType
import org.eclipse.tycho.artifacts.TargetPlatform
import org.eclipse.tycho.core.ee.shared.ExecutionEnvironmentConfigurationStub
import org.eclipse.tycho.core.resolver.shared.MavenRepositoryLocation
import org.eclipse.tycho.core.shared.MavenLogger
import org.eclipse.tycho.p2.resolver.facade.P2ResolutionResult
import org.eclipse.tycho.p2.resolver.facade.P2Resolver
import org.eclipse.tycho.p2.resolver.facade.P2ResolverFactory
import org.eclipse.tycho.p2.target.facade.TargetPlatformConfigurationStub
import org.eclipse.tycho.p2.target.facade.TargetPlatformFactory
import java.io.File
import java.lang.NullPointerException

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

fun Project.p2dependencies(configuration: P2DependencyHandlerScope.() -> Unit) =
		P2DependencyHandlerScope(extensions["p2Dependencies"] as InstallableUnitContainerDesc).configuration()





class P2DependencyHandlerScope(val deps: InstallableUnitContainerDesc) {
	
	operator fun String.invoke(dependencyNotation: InstallableUnitDesc) =
			deps.add(this, dependencyNotation)

	operator fun Configuration.invoke(dependencyNotation: InstallableUnitDesc)=
		deps.add(name, dependencyNotation)
	
	fun eclipsePlugin(id: String, versionRange: String) = InstallableUnitDesc(ArtifactType.TYPE_ECLIPSE_PLUGIN, id, versionRange)

}


open class InstallableUnitContainerDesc(var installableUnits: MutableMap<String, MutableList<InstallableUnitDesc>> = mutableMapOf()) {
	fun add(configurationName: String, iu: InstallableUnitDesc) {
		var ius = installableUnits[configurationName]
		if(ius==null)
			installableUnits[configurationName]=mutableListOf(iu)
		else
			ius.add(iu)
		
	}


}

data class InstallableUnitDesc(var type: String, var id: String, var versionRange: String)


class GradleTycho : Plugin<Project> {
	companion object {
		val TYCHO_INSTALL_ZIP = "tychoInstall"
		val TYCHO_BUNDLES = "tychoBundles"

	}


	override fun apply(project: Project) {
		configureProject(project)
//        addDownloadTask(project)
		project.afterEvaluate {
			createEquinoxEmbedder(project)
			resolveDeps(project)
		}
	}

	fun configureProject(project: Project) {

		with(project) {
			extensions.create("equinoxEmbedder", EquinoxEmbedderDesc::class.java)
			extensions.create("p2Dependencies", InstallableUnitContainerDesc::class.java)

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


	//    static void addDownloadTask(Project project) {
//        project.task("resolveP2",  dependsOn: [project.configurations[TYCHO_INSTALL_ZIP], project.configurations[TYCHO_BUNDLES]]) { doLast{ } }
//    }
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

	fun resolveDeps(project: Project) {
		project.logger.info("resolve p2 dependency")
		val EqEmbDesc = project.extensions.getByType(EquinoxEmbedderDesc::class.java)

		val embedder = EqEmbDesc.embedder ?: throw IllegalStateException("Embedder Is Null")
		try{
		embedder.start();
		val resolverFactory = embedder.getService(P2ResolverFactory::class.java);
		val targetPlatform = resolverFactory.getTargetPlatformFactory().createTargetPlatform(
				targetPlatformStub(EqEmbDesc.p2repo),
				EqEmbDesc.executionEnviroment, null, null);

		(project.extensions.getByName("p2Dependencies") as InstallableUnitContainerDesc).installableUnits.forEach({ conf ->

			val p2Resolver = resolverFactory.createResolver(EqEmbDesc.logger);
			conf.value.forEach({
				p2Resolver.addDependency(it.type, it.id, it.versionRange);
			})
			val dependencies = p2Resolver.resolveDependencies(targetPlatform, null);

			dependencies.forEach {
				it.artifacts.forEach {
					project.logger.info("add " + it.location + " to config " + conf.key)
					project.dependencies.add(conf.key, project.files(it.location))
				}
			}
		})
		}finally{
			embedder.close()
		}
	}

	fun targetPlatformStub(p2repo: MavenRepositoryLocation?): TargetPlatformConfigurationStub {
		val tpConfiguration = TargetPlatformConfigurationStub();
		tpConfiguration.addP2Repository(p2repo);
		tpConfiguration.setForceIgnoreLocalArtifacts(false);
		return tpConfiguration;
	}
}