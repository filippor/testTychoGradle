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



class BundlePlugin : Plugin<Project> {
	override fun apply(project: Project) {
		configureProject(project)
		loadDependenciesFromManifest(project)
		
	}

	fun configureProject(project: Project) {
		P2DependencyPlugin().apply(project)

	}


	fun loadDependenciesFromManifest(project: Project) {
		// obtain BundleDescription class from OSGi to have precise dependency definitions
		val manifest = CaseInsensitiveDictionaryMap<String, String>()
		ManifestElement.parseBundleManifest(FileInputStream(project.file("META-INF/MANIFEST.MF")), manifest)
		val factory = StateObjectFactoryImpl()
		val description = factory.createBundleDescription(UserState(), manifest, null, 1)
		description.requiredBundles.forEach { defineDependency(it, project) }
	}

	fun defineDependency(requiredBundle: BundleSpecification, project: Project) {
		val pluginName = requiredBundle.getName()
		val versionRange = requiredBundle.versionRange
//        checkMappedVersion(versionRange, pluginName, project)

		val localProject = project.rootProject.allprojects.find { it.name == pluginName }
		if (localProject != null && versionRange.includes(Version(localProject.version as String))) {
			// handle dependencies to local projects
			project.dependencies { "compile"(localProject) }
		} else {
			project.p2dependencies {
				"compile"(InstallableUnitDesc(
						ArtifactType.TYPE_ECLIPSE_PLUGIN,
						pluginName,
						versionRange.toString()
				))
			}
		}
	}
}
