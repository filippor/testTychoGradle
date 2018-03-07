/*
 * Copyright (c) 2015 the original author or authors.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Donát Csikós (Gradle Inc.) - initial API and implementation and initial documentation
 */

package it.filippor.tycho

import groovy.lang.Closure
import groovy.util.XmlNodePrinter
import groovy.util.XmlParser
import  it.filippor.tycho.library.Constants
import  it.filippor.tycho.library.*
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.file.FileCollection
import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.tasks.bundling.Zip
import org.gradle.kotlin.dsl.*
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import org.eclipse.internal.provisional.equinox.p2.jarprocessor.JarProcessorExecutor
import org.gradle.tooling.model.GradleTask
import org.eclipse.tycho.p2.tools.publisher.facade.PublisherServiceFactory
import org.eclipse.tycho.core.shared.TargetEnvironment
import org.eclipse.tycho.ReactorProject
import org.eclipse.tycho.artifacts.TargetPlatform
import org.eclipse.tycho.p2.resolver.facade.P2ResolverFactory
import org.eclipse.tycho.core.resolver.shared.MavenRepositoryLocation
import org.eclipse.tycho.p2.target.facade.TargetPlatformConfigurationStub
import org.eclipse.tycho.repository.registry.facade.ReactorRepositoryManagerFacade
/**
 * Gradle plugin for building Eclipse update sites.
 * <p>
 * An example for a valid DSL:
 * <pre>
 * apply plugin: eclipsebuild.UpdateSitePlugin
 *
 * updateSite {
 *   siteDescriptor = file('category.xml')
 *   extraResources = files('epl-v10.html', 'readme.txt')
 *   p2ExtraProperties = ['p2.mirrorsURL' : 'http://www.eclipse.org/downloads/download.php?file=/path/to/repository&format=xml' ]
 *   signing = { ... }
 * }
 * </pre>
 * The {@code siteDescriptor} is the category definition for the P2 update site.
 * The {@code extraResources} enumerates all extra files that should be included in the update site.
 * The {@code signing} closure defining how the update site's artifacts should be signed.
 * The {@code mutateArtifactsXml} enables the project to transform the artifacts.xml file after
 * it is generated to provide extra information about the update site.
 * <p>
 * The main tasks contributed by this plugin are responsible to generate an Eclipse Update Site.
 * They are attached to the 'assemble' task. When executed, all project dependency jars are copied
 * to the build folder, signed and published to the buildDir/repository folder. To include a
 * the local plugin or feature project, the {@code localPlugin} and {@code localFeature}
 * configuration scope should be used. An external plugin can be also included by using the
 * {@code externalPlugin} configuration scope. The last configuration scope is the
 * {@code signedExternalPlugin} which is the same as the externalPlugin except the content
 * is not signed nor conditioned with pack200 when publishing.
 */
class UpdateSitePlugin : Plugin<Project> {
	fun Project.updateSite() = extensions[DSL_EXTENSION_NAME] as Extension
	/**
	 * Extension class to configure the UpdateSite plugin.
	 */
	open
	class Extension {
		var siteDescriptor: File? = null
		var extraResources: FileCollection? = null
		var signing: Closure<Unit>? = null
		var mutateArtifactsXml: Closure<Unit>? = null
	}

	companion object {
		// name of the root node in the DSL
		val DSL_EXTENSION_NAME = "updateSite"

		// task names defined in the plug-in
		val COPY_BUNDLES_TASK_NAME = "copyBundles"
		val NORMALIZE_BUNDLES_TASK_NAME = "normalizeBundles"
		val SIGN_BUNDLES_TASK_NAME = "signBundles"
		val COMPRESS_BUNDLES_TASK_NAME = "compressBundles"
		val CREATE_P2_REPOSITORY_TASK_NAME = "createP2Repository"

		// temporary folder names during build
		val PRE_NORMALIZED_BUNDLES_DIR_NAME = "unconditioned-bundles"
		val UNSIGNED_BUNDLES_DIR_NAME = "unsigned-bundles"
		val SIGNED_BUNDLES_DIR_NAME = "signed-bundles"
		val COMPRESSED_BUNDLES_DIR_NAME = "compressed-bundles"
		val FEATURES_DIR_NAME = "features"
		val PLUGINS_DIR_NAME = "plugins"
		val REPOSITORY_DIR_NAME = "repository"

	}

	override fun apply(project: Project) {
		configureProject(project)
		addTaskCopyBundles(project)
		addTaskNormalizeBundles(project)
		addTaskSignBundles(project)
		addTaskCompressBundles(project)
		addTaskCreateP2Repository(project)
	}

	fun configureProject(project: Project) {
		// apply the Java plugin to have the life-cycle tasks
		project.plugins.apply("java")

		// create scopes for local and external plugins and features
		project.configurations.create("localPlugin")
		project.configurations.create("localFeature")
		project.configurations.create("externalPlugin")
		project.configurations.create("signedExternalPlugin")

		// add the 'updateSite' extension
		project.extensions.create(DSL_EXTENSION_NAME, Extension::class.java)
		var updateSite = project.updateSite()
		updateSite.siteDescriptor = project.file("category.xml")
		updateSite.extraResources = project.files()
		updateSite.signing = null
		updateSite.mutateArtifactsXml = null

		// validate the content
		validateRequiredFilesExist(project)
	}

	fun addTaskCopyBundles(project: Project) {
		val copyBundlesTask = project.task(COPY_BUNDLES_TASK_NAME) {
			group = Constants.gradleTaskGroupName
			description = "Collects the bundles that make up the update site."
			outputs.dir(File(project.buildDir, PRE_NORMALIZED_BUNDLES_DIR_NAME))
			doLast { copyBundles(project) }
		}

		// add inputs for each plugin/feature project once this build script has been evaluated (before that, the dependencies are empty)
		project.afterEvaluate {
			for (projectDependency in project.configurations["localPlugin"].dependencies.withType(ProjectDependency::class.java)) {
				// check if the dependent project is a bundle or feature, once its build script has been evaluated
				val dependency = projectDependency.dependencyProject
				if (dependency.plugins.hasPlugin(BundlePlugin::class.java)) {
					copyBundlesTask.inputs.files(dependency.tasks["jar"].outputs.files)
				} else {
					dependency.afterEvaluate {
						if (dependency.plugins.hasPlugin(BundlePlugin::class.java)) {
							copyBundlesTask.inputs.files(dependency.tasks["jar"].outputs.files)
						}
					}
				}
			}
		}

		project.afterEvaluate {
			for (projectDependency in project.configurations["localFeature"].dependencies.withType(ProjectDependency::class.java)) {
				// check if the dependent project is a bundle or feature, once its build script has been evaluated
				val dependency = projectDependency.dependencyProject
				if (dependency.plugins.hasPlugin(FeaturePlugin::class.java)) {
					copyBundlesTask.inputs.files(dependency.tasks["jar"].outputs.files)
				} else {
					dependency.afterEvaluate {
						if (dependency.plugins.hasPlugin(FeaturePlugin::class.java)) {
							copyBundlesTask.inputs.files(dependency.tasks["jar"].outputs.files)
						}
					}
				}
			}
		}
	}

	fun copyBundles(project: Project) {
		val rootDir = File(project.buildDir, PRE_NORMALIZED_BUNDLES_DIR_NAME)
		val pluginsDir = File(rootDir, PLUGINS_DIR_NAME)
		val featuresDir = File(rootDir, FEATURES_DIR_NAME)

		// delete old content
		if (rootDir.exists()) {
			project.logger.info("Delete bundles directory '${rootDir.absolutePath}'")
			rootDir.deleteRecursively()
		}

		// iterate over all the project dependencies to populate the update site with the plugins and features
		project.logger.info("Copy features and plugins to bundles directory '${rootDir.absolutePath}'")
		for (projectDependency in project.configurations["localPlugin"].dependencies.withType(ProjectDependency::class.java)) {
			val dependency = projectDependency.dependencyProject

			// copy the output jar for each plugin project dependency
			if (dependency.plugins.hasPlugin(BundlePlugin::class.java)) {
				project.logger.debug("Copy plugin project '${dependency.name}' with jar '${dependency.tasks["jar"].outputs.files.singleFile.absolutePath}' to '${pluginsDir}'")
				project.copy {cs->
					cs.from(dependency.tasks["jar"].outputs.files.singleFile)
					cs.into(pluginsDir)
				}
			}
		}

		for (projectDependency in project.configurations["localFeature"].dependencies.withType(ProjectDependency::class.java)) {
			val dependency = projectDependency.dependencyProject

			// copy the output jar for each feature project dependency
			if (dependency.plugins.hasPlugin(FeaturePlugin::class.java)) {
				project.logger.debug("Copy feature project '${dependency.name}' with jar '${dependency.tasks["jar"].outputs.files.singleFile.absolutePath}' to '${pluginsDir}'")
				project.copy {cs->
					cs.from(dependency.tasks["jar"].outputs.files.singleFile)
					cs.into(featuresDir)
				}
			}
		}

		// iterate over all external dependencies and add them to the plugins (this includes the transitive dependencies)
		project.copy {cs->
			cs.from(project.configurations["externalPlugin"])
			cs.into(pluginsDir)
		}
		// if the signing is not enabled, then remove existing signatures
		if ((project.extensions[DSL_EXTENSION_NAME] as Extension).signing == null) {
			// extract the jars and delete the the sums from the manifest file
			pluginsDir.listFiles().forEach { jar ->
				val extractedJar = File(jar.parentFile, jar.name + ".u")
				project.ant.invokeMethod("unzip", mapOf(
						"src" to jar,
						"dest" to File(jar.parentFile, jar.name + ".u"),
						"overwrite" to true
				))
				jar.delete()
				val manifest = File(extractedJar, "META-INF/MANIFEST.MF")
				removeSignaturesFromManifest(manifest)
			}
			// re-jar the content without the signature files
			pluginsDir.listFiles().forEach { extractedJar ->
				val jar = File(extractedJar.parentFile, extractedJar.name.substring(0, extractedJar.name.length - 2))
				project.ant.invokeMethod("zip", sequenceOf(
						"src" to jar,
						"dest" to File(jar.parentFile, jar.name + ".u"),
						"overwrite" to true,
						"exclude" to ("name" to "**/*.RSA"),
						"exclude" to ("name" to "**/*.DSA"),
						"exclude" to ("name" to "**/*.SF")
				))
				extractedJar.deleteRecursively()
			}
		}
	}

	fun removeSignaturesFromManifest(input: File) {
		val output = StringBuilder()
		var newLineFound = false
		input.forEachLine { line ->
			if (!newLineFound) {
				output.append(line)
				if (line == "") {
					newLineFound = true
				} else {
					output.append("\n")
				}
			}
		}
		input.writeText(output.toString())
	}

	fun addTaskNormalizeBundles(project: Project) {
		project.task(NORMALIZE_BUNDLES_TASK_NAME) {
			//dependsOn(COPY_BUNDLES_TASK_NAME, ":${BuildDefinitionPlugin.TASK_NAME_INSTALL_TARGET_PLATFORM}")
			group = Constants.gradleTaskGroupName
			description = "Repacks the bundles that make up the update site using the pack200 tool."
			inputs.dir(File(project.buildDir, PRE_NORMALIZED_BUNDLES_DIR_NAME))
			outputs.dir(File(project.buildDir, UNSIGNED_BUNDLES_DIR_NAME))
			doLast { normalizeBundles(project) }
		}
	}


	fun normalizeBundles(project: Project) {
		var options = JarProcessorExecutor.Options()
		options.processAll = true
		options.repack = true
		options.outputDir = File(project.buildDir, UNSIGNED_BUNDLES_DIR_NAME).absolutePath
		options.input = File(project.buildDir, PRE_NORMALIZED_BUNDLES_DIR_NAME)
		JarProcessorExecutor().runJarProcessor(options)

		/*

        project.javaexec {

            it.main = "org.eclipse.equinox.internal.p2.jarprocessor.Main"
            it.classpath (Config . on (project).jarProcessorJar)
            it.args = mutableListOf("-processAll",
            "-repack",
            "-outputDir", File (project.buildDir, UNSIGNED_BUNDLES_DIR_NAME).absolutePath,
                File (project.buildDir, PRE_NORMALIZED_BUNDLES_DIR_NAME).absolutePath
            )
        }
        */
	}

	fun addTaskSignBundles(project: Project) {
		project.task(SIGN_BUNDLES_TASK_NAME) {
			dependsOn(NORMALIZE_BUNDLES_TASK_NAME)
			group = Constants.gradleTaskGroupName
			description = "Signs the bundles that make up the update site."
			inputs.dir(File(project.buildDir, UNSIGNED_BUNDLES_DIR_NAME))
			outputs.dir(File(project.buildDir, SIGNED_BUNDLES_DIR_NAME))
			doLast { (project.updateSite().signing?.invoke(File(project.buildDir, UNSIGNED_BUNDLES_DIR_NAME), File(project.buildDir, SIGNED_BUNDLES_DIR_NAME))) }
			doLast { copyOverAlreadySignedBundles(project, "$SIGNED_BUNDLES_DIR_NAME/$PLUGINS_DIR_NAME") }
			onlyIf { project.updateSite().signing != null }
		}
	}

	fun copyOverAlreadySignedBundles(project: Project, folderInBuildDir: String) {
		project.copy {cs->
			cs.from(project.configurations["signedExternalPlugin"])
			cs.into(File(project.buildDir, folderInBuildDir))
		}

	}

	fun addTaskCompressBundles(project: Project) {
		project.task(COMPRESS_BUNDLES_TASK_NAME) {
			dependsOn(
					NORMALIZE_BUNDLES_TASK_NAME, SIGN_BUNDLES_TASK_NAME/*, ":${BuildDefinitionPlugin.TASK_NAME_INSTALL_TARGET_PLATFORM}"*/)
			group = Constants.gradleTaskGroupName
			description = "Compresses the bundles that make up the update using the pack200 tool."
			project.afterEvaluate {
				inputs.dir(if (project.updateSite().signing != null)
					File(project.buildDir, SIGNED_BUNDLES_DIR_NAME)
				else File(project.buildDir, UNSIGNED_BUNDLES_DIR_NAME))
			}
			outputs.dir(File(project.buildDir, COMPRESSED_BUNDLES_DIR_NAME))
			doLast { compressBundles(project) }
		}

	}

	fun compressBundles(project: Project) {
		var uncompressedBundles =
				if (project.updateSite().signing != null)
					File(project.buildDir, SIGNED_BUNDLES_DIR_NAME)
				else File(project.buildDir, UNSIGNED_BUNDLES_DIR_NAME)
		var compressedBundles = File(project.buildDir, COMPRESSED_BUNDLES_DIR_NAME)

		// copy over all bundles
		project.copy {cs->
			cs.from(uncompressedBundles)
			cs.into(compressedBundles)
		}

		// compress and store them in the same folder
		var options = JarProcessorExecutor.Options()
		options.pack = true
		options.outputDir = compressedBundles.absolutePath
		options.input = compressedBundles
		JarProcessorExecutor().runJarProcessor(options)


//		org.eclipse.equinox.internal.p2.jarprocessor.Main.main(arrayOf<String>(
//                "-pack","-outputDir",compressedBundles.absolutePath,
//                compressedBundles.absolutePath
//        		)
//        )

	}

	fun addTaskCreateP2Repository(project: Project) {
		val createP2RepositoryTask = project.task(CREATE_P2_REPOSITORY_TASK_NAME) { 
			//    dependsOn(
			//COMPRESS_BUNDLES_TASK_NAME, ":${BuildDefinitionPlugin.TASK_NAME_INSTALL_TARGET_PLATFORM}")
			group = Constants.gradleTaskGroupName
			description = "Generates the P2 repository."
			inputs.file(project.updateSite().siteDescriptor)
			inputs.files(project.updateSite().extraResources)
			inputs.dir(File(project.buildDir, COMPRESSED_BUNDLES_DIR_NAME))
			outputs.dir(File(project.buildDir, REPOSITORY_DIR_NAME))
			doLast { createP2Repository(project) }
		}

		project.tasks["assemble"].dependsOn(createP2RepositoryTask)
	}

	fun createP2Repository(project: Project) {
		val repositoryDir = File(project.buildDir, REPOSITORY_DIR_NAME)

		// delete old content
		if (repositoryDir.exists()) {
			project.logger.info("Delete P2 repository directory '${repositoryDir.absolutePath}'")
			repositoryDir.deleteRecursively()
		}

		// create the P2 update site
		publishContentToLocalP2Repository(project, repositoryDir)

		// add custom properties to the artifacts.xml file
		val mutateArtifactsXml = project.updateSite().mutateArtifactsXml
		if (mutateArtifactsXml != null) {
			updateArtifactsXmlFromArchive(project, repositoryDir, mutateArtifactsXml)
		}
	}

	

 fun publishContentToLocalP2Repository(project:Project,  repositoryDir:File)
    {
        val rootDir =  File(project.buildDir, COMPRESSED_BUNDLES_DIR_NAME)

        // publish features/plugins to the update site
        project.logger.info("Publish plugins and features from '${rootDir.absolutePath}' to the update site '${repositoryDir.absolutePath}'")
        
		var eqEmbDesc = project.getEnbedderDesc()
		val embedder = eqEmbDesc.embedder ?: throw IllegalStateException("Embedder Is Null")
		try {
			embedder.start();
			var reactPrj = ReactorProjectStub(project)

			val resolverFactory = embedder.getService(ReactorRepositoryManagerFacade::class.java);
			println(resolverFactory)
			val targetPlatform = resolverFactory.computePreliminaryTargetPlatform(reactPrj,targetPlatformStub(eqEmbDesc.p2repo),
					eqEmbDesc.executionEnviroment, mutableListOf<ReactorProject>( reactPrj), null)
			println(targetPlatform)
			
			resolverFactory.computeFinalTargetPlatform(reactPrj,mutableListOf(reactPrj ))
			println("final target")
			var publishing = resolverFactory.getPublishingRepository(reactPrj)
			println(publishing)
			
			var serFac = embedder.getService(PublisherServiceFactory::class.java)
			println(serFac)
//			var targetEnv :TargetEnvironment


			//reactPrj.setContextValue(TargetPlatform.FINAL_TARGET_PLATFORM_KEY,targetPlatform)
			var publ = serFac.createPublisher(reactPrj,listOf())
			println(publ)
			//publ.
		}finally{
			embedder.close()
		} 
		/*
        project.exec {
            commandLine(Config.on(project).eclipseSdkExe,
                    '-nosplash',
                    '-application', 'org.eclipse.equinox.p2.publisher.FeaturesAndBundlesPublisher',
                    '-metadataRepository', repositoryDir.toURI().toURL(),
                    '-artifactRepository', repositoryDir.toURI().toURL(),
                    '-source', rootDir,
                    '-compress',
                    '-publishArtifacts',
                    '-reusePack200Files',
                    '-configs', 'ANY',
                    '-consoleLog')
        }
        */
        // publish P2 category defined in the category.xml to the update site
        project.logger.info("Publish categories defined in '${project.updateSite().siteDescriptor!!.absolutePath}' to the update site '${repositoryDir.absolutePath}'")
        /*project.exec {
            commandLine(Config.on(project).eclipseSdkExe,
                    '-nosplash',
                    '-application', 'org.eclipse.equinox.p2.publisher.CategoryPublisher',
                    '-metadataRepository', repositoryDir.toURI().toURL(),
                    '-categoryDefinition', project.updateSite.siteDescriptor.toURI().toURL(),
                    '-compress',
                    '-consoleLog')
        }
        */
        // copy the extra resources to the update site
        project.copy {cs->
            cs.from (project . updateSite() . extraResources)
            cs.into (repositoryDir)
        }
    }
	fun targetPlatformStub(p2repo: MavenRepositoryLocation?): TargetPlatformConfigurationStub {
		val tpConfiguration = TargetPlatformConfigurationStub();
		tpConfiguration.addP2Repository(p2repo);
		tpConfiguration.setForceIgnoreLocalArtifacts(false);
		return tpConfiguration;
	}

	fun updateArtifactsXmlFromArchive(project: Project, repositoryLocation: File, mutateArtifactsXml: Closure<Unit>) {
		// get the artifacts.xml file from the artifacts.jar
		val artifactsJarFile = File(repositoryLocation, "artifacts.jar")
		val artifactsXmlFile = project.zipTree(artifactsJarFile).matching { "artifacts.xml" }.singleFile

		// parse the xml
		val xml = XmlParser().parse(artifactsXmlFile)

		// apply artifacts.xml customization (append mirrors url, link to stat servers, etc.)
		mutateArtifactsXml(xml)

		// write the updated artifacts.xml back to its source
		// the artifacts.xml is a temporary file hence it has to be copied back to the archive
		XmlNodePrinter(PrintWriter(FileWriter(artifactsXmlFile)), "  ", "'").print(xml)

		//project.ant.zip(update: true, filesonly: true, destfile: artifactsJarFile) { fileset(file: artifactsXmlFile) }
		project.ant.invokeMethod("zip", sequenceOf("update" to true, "destfile" to artifactsJarFile, "fileset" to sequenceOf("file" to artifactsXmlFile)))
	}

	fun validateRequiredFilesExist(project: Project) {
		project.gradle.taskGraph.whenReady {
			// make sure the required descriptors exist
			assert(project.file(project.updateSite().siteDescriptor).exists())
		}
	}

}
