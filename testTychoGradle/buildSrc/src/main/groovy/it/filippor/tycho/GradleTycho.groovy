package it.filippor.tycho

import it.filippor.tycho.library.EquinoxEmbedder
import it.filippor.tycho.library.MavenGradleLoggerAdapter
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.*

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


class GradleTycho implements Plugin<Project> {

    static final String TYCHO_INSTALL_ZIP = "tychoInstall"
    static final String TYCHO_BUNDLES = "tychoBundles"
    static String tychoVersion = "1.0.0"


    /**
     *  Extension class providing top-level content of the DSL definition for the plug-in.
     */
    static class EquinoxEmbedderDesc {

        List<String> extraSystemPackage

        MavenRepositoryLocation p2repo

        void repo(String name, String url) {
            p2repo = new MavenRepositoryLocation(name, URI.create(url));
        }

        ExecutionEnvironmentConfigurationStub executionEnviroment

        EquinoxEmbedder embedder

        MavenLogger logger
    }

    static class InstallableUnitContainerDesc{
        Map<String,List<InstallableUnitDesc>> installableUnits = new HashMap();
        void add(String configurationName,InstallableUnitDesc iu) {
            List<InstallableUnitDesc> ius = installableUnits.get(configurationName);
            if(ius==null) {
                ius = new ArrayList();
                installableUnits.put(configurationName, ius)
            }
            ius.add(iu)
        }
        InstallableUnitDesc eclipsePlugin(String id, String versionRange) {

            return new InstallableUnitDesc(ArtifactType.TYPE_ECLIPSE_PLUGIN,id,versionRange)
        }
    }

    static class InstallableUnitDesc{
        public InstallableUnitDesc(String type, String id, String versionRange) {
            this.type = type;
            this.id = id;
            this.versionRange = versionRange;
        }
        String type
        String id
        String versionRange
    }

    static final String EXTRA_TYCHO_SYSTEM_PACKAGES = "extraTychoSystemPackages"
    @Override
    public void apply(Project project) {
        configureProject(project)
//        addDownloadTask(project)
        project.afterEvaluate{
            createEquinoxEmbedder(project)
            resolveDeps(project)
        }
    }

    static void configureProject(Project project) {

        println "configure Test"

        project.extensions.create("equinoxEmbedder", EquinoxEmbedderDesc)
        project.extensions["equinoxEmbedder"].extraSystemPackage=
                [
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
                ]

        project.configurations.create(TYCHO_INSTALL_ZIP);
        project.dependencies.add(
                TYCHO_INSTALL_ZIP,"org.eclipse.tycho:tycho-bundles-external:${tychoVersion}@zip");
        project.configurations.create(TYCHO_BUNDLES);

        project.dependencies.add(TYCHO_BUNDLES, "org.eclipse.tycho:org.eclipse.tycho.p2.resolver.impl:${tychoVersion}");
        project.dependencies.add(TYCHO_BUNDLES, "org.eclipse.tycho:org.eclipse.tycho.p2.maven.repository:${tychoVersion}");
        project.dependencies.add(TYCHO_BUNDLES, "org.eclipse.tycho:org.eclipse.tycho.p2.tools.impl:${tychoVersion}");


        project.extensions["equinoxEmbedder"].p2repo = new MavenRepositoryLocation("Oxygen", URI.create("http://download.eclipse.org/releases/oxygen"));
        project.extensions["equinoxEmbedder"].executionEnviroment = new ExecutionEnvironmentConfigurationStub("JavaSE-1.8");


        project.extensions.create("p2dependencies", InstallableUnitContainerDesc)
    }




//    static void addDownloadTask(Project project) {
//        project.task("resolveP2",  dependsOn: [project.configurations[TYCHO_INSTALL_ZIP], project.configurations[TYCHO_BUNDLES]]) { doLast{ } }
//    }

    static void createEquinoxEmbedder(Project project) {
        project.logger.info("create equinox embedder")
        File sdkArchive = project.configurations[TYCHO_INSTALL_ZIP].getSingleFile()

        project.ant.unzip(src: sdkArchive, dest: sdkArchive.parentFile, overwrite: true)
        File installLocation = new File(sdkArchive.parentFile,"eclipse");

        def logger = new MavenGradleLoggerAdapter(project.logger)
        EquinoxEmbedder embedder = new EquinoxEmbedder(
                installLocation,
                project.configurations[TYCHO_BUNDLES].getFiles(),
                project.extensions["equinoxEmbedder"].extraSystemPackage,
                new File(project.buildDir,"p2"),
                false,
                logger
                );
        project.extensions["equinoxEmbedder"].embedder=embedder
        project.extensions["equinoxEmbedder"].logger=logger
    }

    static void resolveDeps(Project project) {
        project.logger.info("resolve p2 dependency")
        EquinoxEmbedder embedder = project.extensions["equinoxEmbedder"].embedder
        MavenLogger logger = project.extensions["equinoxEmbedder"].logger
        try{

            embedder.start();
            def resolverFactory = embedder.getService(P2ResolverFactory.class);

            TargetPlatformFactory platformFactory = resolverFactory.getTargetPlatformFactory();

            TargetPlatform targetPlatform = getTargetPlatform(project.extensions["equinoxEmbedder"].executionEnviroment, project.extensions["equinoxEmbedder"].p2repo, platformFactory);


            project.extensions["p2dependencies"].installableUnits.each({conf ->

                P2Resolver p2Resolver = resolverFactory.createResolver(logger);
                conf.value.each({
                    p2Resolver.addDependency(it.type, it.id, it.versionRange);
                })
                List<P2ResolutionResult> dependencies = p2Resolver.resolveDependencies(targetPlatform, null);

                dependencies.each{
                    it.artifacts.each{
                        project.logger.info("add " + it.location + " to config " + conf.key)
                        project.dependencies.add(conf.key, project.files(it.location))
                    }
                }
            })
        }finally {
            embedder.close();
        }
    }

    static TargetPlatform getTargetPlatform(ExecutionEnvironmentConfigurationStub executionEnviroment, MavenRepositoryLocation p2repo, TargetPlatformFactory platformFactory) {
        TargetPlatformConfigurationStub tpConfiguration = targetPlatformStub(p2repo);
        TargetPlatform targetPlatform = platformFactory.createTargetPlatform(tpConfiguration, executionEnviroment, null, null);
        return targetPlatform;
    }

    static TargetPlatformConfigurationStub targetPlatformStub(MavenRepositoryLocation p2repo) {
        TargetPlatformConfigurationStub tpConfiguration = new TargetPlatformConfigurationStub();
        tpConfiguration.addP2Repository(p2repo);
        tpConfiguration.setForceIgnoreLocalArtifacts(false);
        return tpConfiguration;
    }
}