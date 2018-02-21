package it.filippor.tycho

import org.eclipse.osgi.framework.util.CaseInsensitiveDictionaryMap
import org.eclipse.osgi.internal.resolver.StateObjectFactoryImpl
import org.eclipse.osgi.internal.resolver.UserState
import org.eclipse.osgi.service.resolver.BundleSpecification
import org.eclipse.osgi.util.ManifestElement
import org.eclipse.tycho.ArtifactType
import org.eclipse.tycho.core.resolver.shared.MavenRepositoryLocation
import org.eclipse.tycho.p2.resolver.facade.P2ResolverFactory
import org.eclipse.tycho.p2.target.facade.TargetPlatformConfigurationStub
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.*
import java.io.FileInputStream
import org.osgi.framework.Version


fun Project.p2dependencies(configuration: P2DependencyHandlerScope.() -> Unit) =
		P2DependencyHandlerScope(extensions["p2Dependencies"] as InstallableUnitContainerDesc).configuration()


data class P2DependencyHandlerScope(val deps: InstallableUnitContainerDesc) {

	operator fun String.invoke(dependencyNotation: InstallableUnitDesc) =
			deps.add(this, dependencyNotation)

	operator fun Configuration.invoke(dependencyNotation: InstallableUnitDesc) =
			deps.add(name, dependencyNotation)

	fun eclipsePlugin(id: String, versionRange: String) = InstallableUnitDesc(ArtifactType.TYPE_ECLIPSE_PLUGIN, id, versionRange)

}


open class InstallableUnitContainerDesc(var installableUnits: MutableMap<String, MutableList<InstallableUnitDesc>> = mutableMapOf()) {
	fun add(configurationName: String, iu: InstallableUnitDesc) {
		var ius = installableUnits[configurationName]
		if (ius == null)
			installableUnits[configurationName] = mutableListOf(iu)
		else
			ius.add(iu)

	}


}

data class InstallableUnitDesc(var type: String, var id: String, var versionRange: String)

class P2DependencyPlugin : Plugin<Project> {
	override fun apply(project: Project) {
		configureProject(project)
		project.afterEvaluate {
			resolveDeps(project)
		}
	}

	fun configureProject(project: Project) {
		project.extensions.create("p2Dependencies", InstallableUnitContainerDesc::class.java)

	}
	fun resolveDeps(project: Project) {
		project.logger.info("resolve p2 dependency")
		val EqEmbDesc = project.extensions.getByType(EquinoxEmbedderDesc::class.java)

		val embedder = EqEmbDesc.embedder ?: throw IllegalStateException("Embedder Is Null")
		try {
			embedder.start();
			val resolverFactory = embedder.getService(P2ResolverFactory::class.java);
			val targetPlatform = resolverFactory.getTargetPlatformFactory().createTargetPlatform(
					targetPlatformStub(EqEmbDesc.p2repo),
					EqEmbDesc.executionEnviroment, null, null);

			(project.extensions.getByName("p2Dependencies") as InstallableUnitContainerDesc).installableUnits.forEach { conf ->

				val p2Resolver = resolverFactory.createResolver(EqEmbDesc.logger);
				conf.value.forEach {
					p2Resolver.addDependency(it.type, it.id, it.versionRange);
				}
				val dependencies = p2Resolver.resolveDependencies(targetPlatform, null);

				dependencies.forEach {
					it.artifacts.forEach {
						project.logger.info("add " + it.location + " to config " + conf.key)
						project.dependencies.add(conf.key, project.files(it.location))
					}
				}
			}
		} finally {
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
