package it.filippor.tycho

import it.filippor.tycho.library.PluginUtils
import it.filippor.tycho.library.Constants
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
import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.tasks.Copy
import java.io.OutputStream
import java.io.PrintWriter
import groovy.util.XmlParser


class BundlePlugin : Plugin<Project> {
	companion object {
		val TASK_NAME_UPDATE_LIBS = "updateLibs"
		val TASK_NAME_COPY_LIBS = "copyLibs"
		val TASK_NAME_UPDATE_MANIFEST = "updateManifest"
	}


	override fun apply(project: Project) {
		configureProject(project)
		loadDependenciesFromManifest(project)

		addTaskCopyLibs(project)
		addTaskUpdateManifest(project)
		addTaskUpdateLibs(project)
	}

	fun configureProject(project: Project) {
//		project.plugins.apply("JavaPlugin")
		P2DependencyPlugin().apply(project)

		project.configurations.create("bundled")
		project.configurations.create("bundledSource")
		project.configurations { "compile"().extendsFrom("bundled"()) }

		// make sure the required descriptors exist
		assert(project.file("build.properties").exists())
		assert(project.file("META-INF/MANIFEST.MF").exists())

		// use the same MANIFEST.MF file as it is in the project except the Bundle-Version
		PluginUtils.updatePluginManifest(project)
		// parse build.properties and sync it with output jar
		PluginUtils.configurePluginJarInput(project)
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

	fun addTaskCopyLibs(project: Project) {
		project.tasks {
			TASK_NAME_COPY_LIBS(Copy::class) {
				dependsOn(
						project.configurations["bundled"],
						project.configurations["bundledSource"]
						//":${BuildDefinitionPlugin.TASK_NAME_INSTALL_TARGET_PLATFORM}"
				)
				group = Constants.gradleTaskGroupName
				description = "Copies the bundled dependencies into the lib folder."

				val libDir = project.file("lib")

				// before the update delete all the libraries that are currently in the lib folder
				doFirst {
					libDir.listFiles().forEach {
						f ->
						if (f.toString().endsWith(".jar")) {
							logger.info("Deleting ${f.name}")
							f.delete()
						}
					}
				}

				// copy the dependencies to the 'libs' folder
				into(libDir)
				from(project.configurations["bundled"])
				from(project.configurations["bundledSource"])
			}
		}
	}

	fun addTaskUpdateManifest(project: Project) {
		project.tasks {
			TASK_NAME_UPDATE_MANIFEST {
				dependsOn(project.configurations["bundled"])
				group = Constants.gradleTaskGroupName
				description = "Updates the manifest file with the bundled dependencies."
				doLast { updateManifest(project) }
			}
		}
	}

	fun updateManifest(project: Project) {
		// don't write anything if there is no bundled dependency
		if (project.configurations["bundled"].dependencies.isEmpty()) {
			return
		}

		var manifest = project.file("META-INF/MANIFEST.MF")
		project.logger.info("Update project manifest '${manifest.absolutePath}'")
		var lines = manifest.readLines()
		var i = 0
		manifest.delegateClosureOf<PrintWriter> {
			var out = this
			// copy file upto line with 'Bundle-ClassPath: .'
			while (i < lines.size && !lines[i].startsWith("Bundle-ClassPath: .,")) {
				out.println(lines[i])
				i++
			}

			out.print("Bundle-ClassPath: .,")

			// add a sorted list of jar file names under the Bundle-Classpath section
			var comma = false
			val bundledConfig = project.configurations["bundled"].toList()

			bundledConfig.sortedBy { it.name }.forEach {
				jarFile ->
				if (jarFile.toString().endsWith(".jar")) {
					if (comma) {
						out.println(',')
					} else {
						out.println()
					}
					val name = jarFile.getName()
					out.print(" lib/$name")
					comma = true
				}
			}
			out.println()

			// skip lines up to 'Export-Package: '
			while (i < lines.size && !lines[i].startsWith("Export-Package: ")) {
				i++
			}

			// copy the remaining lines
			while (i < lines.size) {
				out.println(lines [i])
				i++
			}
		}
		project.logger.debug("Manifest content:\n${manifest.readText()}")

/*
				// update the .classpath file
				val classpathFile = project.file(".classpath")
				project.logger.info("Update .classpath file '${classpathFile.absolutePath}'")
				val classpathXml = XmlParser().parse(classpathFile)
				// delete all nodes pointing to the lib folder
				classpathXml.findAll { it.name().equals('classpathentry') && it.@ path . startsWith ('lib/') }.each { classpathXml.remove(it) }
				// re-create the deleted nodes with the 'sourcepath' attribute
				project.configurations.bundled.sort { it.name }.each {
					File jarFile ->
					def name = jarFile . getName ()
					def nameWithoutExtension = name . substring (0, name.lastIndexOf('.'))
					new Node (classpathXml, 'classpathentry', ['exported' : 'true', 'kind' : 'lib', 'path' : "lib/$name", 'sourcepath' : "lib/${nameWithoutExtension}-sources.jar"])
				}
				new XmlNodePrinter (new PrintWriter (new FileWriter (classpathFile))).print(classpathXml)
				project.logger.debug(".classpath content:\n${classpathFile.text}")
*/
	}

	fun addTaskUpdateLibs(project: Project) {
		project.tasks {
			TASK_NAME_UPDATE_LIBS {
				dependsOn(
						TASK_NAME_COPY_LIBS,
						TASK_NAME_UPDATE_MANIFEST
				)
				group = Constants.gradleTaskGroupName
				description = "Copies the bundled dependencies into the lib folder and updates the manifest file."
			}
		}
	}
}

		
