package it.filippor.tycho.library;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Dictionary;
import java.util.Hashtable;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import it.filippor.tycho.library.lock.FileLockServiceImpl;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleException;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.framework.ServiceReference;

import org.eclipse.core.runtime.adaptor.EclipseStarter;
import org.eclipse.tycho.core.resolver.shared.MavenRepositorySettings;
import org.eclipse.tycho.core.shared.MavenContext;
import org.eclipse.tycho.core.shared.MavenContextImpl;
import org.eclipse.tycho.core.shared.MavenLogger;
import org.eclipse.tycho.locking.facade.FileLockService;

//from org.eclipse.sisu.equinox.embedder.internal.DefaultEquinoxEmbedder

public class EquinoxEmbedder implements AutoCloseable {


    static Logger logger = Logger.getLogger(EquinoxEmbedder.class.getName());
    private File tempSecureStorage;
    private File tempEquinoxDir;

    private BundleContext frameworkContext;
    private MavenRepositorySettingsProvider repositorySettingsProvider;
    private MavenContext mavenContext;
    private File installationLocation;
    private Map<String, String> platformProperties;
    private Set<File> bundleLocations;
    private List<String> extraSystemPackages;
    private FileLockService fileLockService;
    private MavenLogger mavenLogger;

    public EquinoxEmbedder(File installLocation,Set<File> bundleLocations,List<String>extraSystemPackages,File localRepo,boolean isOffline, MavenLogger mavenLogger ) {
        this.installationLocation = installLocation;

        this.platformProperties = new LinkedHashMap<>();

        this.bundleLocations = bundleLocations;
        this.extraSystemPackages = extraSystemPackages;

        //service
        this.repositorySettingsProvider = new MavenRepositorySettingsProvider();

        this.mavenLogger = mavenLogger;

        Properties globalProps = new Properties();
        this.mavenContext = new MavenContextImpl(localRepo, isOffline, this.mavenLogger, globalProps);

        this.fileLockService = new FileLockServiceImpl();


    }

    public <T> T getService(Class<T> clazz) {
        return getService(clazz, null);
    }

    public <T> T getService(Class<T> clazz, String filter) {
        checkStarted();

        // TODO technically, we're leaking service references here
        Collection<ServiceReference<T>> serviceReferences;
        try {
            serviceReferences = this.frameworkContext.getServiceReferences(clazz, filter);
        } catch (InvalidSyntaxException e) {
            throw new IllegalArgumentException(e);
        }

        if (serviceReferences == null || serviceReferences.size() == 0) {
            StringBuilder sb = new StringBuilder();
            sb.append("Service is not registered class='").append(clazz).append("'");
            if (filter != null) {
                sb.append("filter='").append(filter).append("'");
            }
            throw new IllegalStateException(sb.toString());
        }

        return this.frameworkContext.getService(serviceReferences.iterator().next());
    }

    public void doStart() throws Exception {
        System.setProperty("osgi.framework.useSystemProperties", "false"); //$NON-NLS-1$ //$NON-NLS-2$

        // end init

        // File frameworkDir = new File("C:/Users/ics_f/test/repo");
        String frameworkLocation = this.installationLocation.getAbsolutePath();

        this.platformProperties.put("osgi.install.area", frameworkLocation);
        this.platformProperties.put("osgi.syspath", frameworkLocation + "/plugins");
        this.platformProperties.put("osgi.configuration.area", copyToTempFolder(new File(this.installationLocation, "configuration")));

        final StringBuilder bundles = new StringBuilder();
        addBundlesDir(bundles, new File(this.installationLocation, "plugins").listFiles(), false);

        for (File location : this.bundleLocations) {
            if (bundles.length() > 0) {
                bundles.append(',');
            }
            bundles.append(getReferenceUrl(location));
        }
        String string = bundles.toString();
        this.platformProperties.put("osgi.bundles", string);

        // this tells framework to use our classloader as parent, so it can see classes that we
        // see
        this.platformProperties.put("osgi.parentClassloader", "fwk");

        // make the system bundle export the given packages and load them from the parent
        this.platformProperties.put("org.osgi.framework.system.packages.extra", formatExtraSystemPackage(this.extraSystemPackages));

        // TODO switch to org.eclipse.osgi.launch.Equinox
        // EclipseStarter is not helping here
        EclipseStarter.setInitialProperties(this.platformProperties);
        EclipseStarter.startup(getNonFrameworkArgs(), null);
        this.frameworkContext = EclipseStarter.getSystemBundleContext();

        activateBundlesInWorkingOrder();

        registerService(MavenRepositorySettings.class, this.repositorySettingsProvider);
        registerService(MavenContext.class, this.mavenContext);
        registerService(FileLockService.class, this.fileLockService);
    }

    private String formatExtraSystemPackage(final List<String> extraSystemPackages) {
        StringBuilder sb = new StringBuilder();
        for (String pkg : extraSystemPackages) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(pkg);
        }
        return sb.toString();
    }


    public <T> void registerService(Class<T> clazz, T service) {
        registerService(clazz, service, new Hashtable<String, Object>(1));
    }

    public <T> void registerService(Class<T> clazz, T service, Dictionary<String, ?> properties) {
        // don't need to call checkStarted here because EmbeddedEquinox instances are already
        // started
        this.frameworkContext.registerService(clazz, service, properties);
    }

    private void activateBundlesInWorkingOrder() {
        // activate bundles which need to do work in their respective activator; stick to a working
        // order (cf. bug 359787)
        // TODO this order should come from the EquinoxRuntimeLocator
        tryActivateBundle("org.eclipse.equinox.ds");
        tryActivateBundle("org.eclipse.equinox.registry");
        tryActivateBundle("org.eclipse.core.net");
    }

    private void tryActivateBundle(String symbolicName) {
        for (Bundle bundle : this.frameworkContext.getBundles()) {
            if (symbolicName.equals(bundle.getSymbolicName())) {
                try {
                    bundle.start(Bundle.START_TRANSIENT); // don't have OSGi remember the autostart
                                                          // setting; want to start these bundles
                                                          // manually to control the start order
                } catch (BundleException e) {
                    logger.log(Level.WARNING, "Could not start bundle " + bundle.getSymbolicName(), e);
                }
            }
        }
    }

    private String[] getNonFrameworkArgs() {
        try {
            this.tempSecureStorage = File.createTempFile("tycho", "secure_storage");
            this.tempSecureStorage.deleteOnExit();
        } catch (IOException e) {
            throw new RuntimeException("Could not create Tycho secure store file in temp dir " + System.getProperty("java.io.tmpdir"), e);
        }

        List<String> nonFrameworkArgs = new ArrayList<>();
        nonFrameworkArgs.add("-eclipse.keyring");
        nonFrameworkArgs.add(this.tempSecureStorage.getAbsolutePath());
        // TODO nonFrameworkArgs.add("-eclipse.password");
        if (this.mavenLogger.isDebugEnabled()) {
            nonFrameworkArgs.add("-debug");
            nonFrameworkArgs.add("-consoleLog");
        }
        return nonFrameworkArgs.toArray(new String[0]);
    }

    private String copyToTempFolder(File configDir) throws IOException {
        File equinoxTmp = File.createTempFile("tycho", "equinox");
        if (!(equinoxTmp.delete() && equinoxTmp.mkdirs())) {
            throw new IOException("Could not create temp dir " + equinoxTmp);
        }
        File tempConfigDir = new File(equinoxTmp, "config");
        if (!(tempConfigDir.mkdirs())) {
            throw new IOException("Could not create temp config dir " + tempConfigDir);
        }
        Files.copy(configDir.toPath().resolve("config.ini"), tempConfigDir.toPath().resolve("config.ini"));
        // FileUtils.copyFileToDirectory(new File(configDir, "config.ini"), tempConfigDir);
        this.tempEquinoxDir = equinoxTmp;
        return tempConfigDir.getAbsolutePath();
    }

    private void addBundlesDir(StringBuilder bundles, File[] files, boolean absolute) {
        if (files != null) {
            for (File file : files) {
                if (isFrameworkBundle(file)) {
                    continue;
                }

                if (bundles.length() > 0) {
                    bundles.append(',');
                }

                if (absolute) {
                    bundles.append(getReferenceUrl(file));
                } else {
                    String name = file.getName();
                    int verIdx = name.indexOf('_');
                    if (verIdx > 0) {
                        bundles.append(name.substring(0, verIdx));
                    } else {
                        throw new RuntimeException("File name doesn't match expected pattern: " + file);
                    }
                }
            }
        }
    }

    private void checkStarted() {
        try {
            start();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public synchronized void start() throws Exception {
        if (this.frameworkContext != null) {
            return;
        }

//        if ("Eclipse".equals(System.getProperty("org.osgi.framework.vendor"))) {
//            throw new IllegalStateException(
//                    "Cannot run multiple Equinox instances in one build. Consider configuring the Tycho build extension, so that all mojos using Tycho functionality share the same Equinox runtime.");
//        }

        // https://bugs.eclipse.org/bugs/show_bug.cgi?id=308949
        // restore TCCL to make sure equinox classloader does not leak into our clients
        ClassLoader tccl = Thread.currentThread().getContextClassLoader();
        try {
            doStart();
        } finally {
            Thread.currentThread().setContextClassLoader(tccl);
        }
    }

    protected boolean isFrameworkBundle(File file) {
        return file.getName().startsWith("org.eclipse.osgi_");
    }

    String getReferenceUrl(File file) {
        // TODO replace this by URI.toString once Eclipse bug #328926 is resolved
        return "reference:" + "file:" + file.getAbsoluteFile().toURI().normalize().getPath();
    }

    @Override
    public void close() throws Exception {
        if (this.frameworkContext != null) {
            try {
                EclipseStarter.shutdown();
            } catch (Exception e) {
                logger.log(Level.SEVERE, "Exception while shutting down equinox", e);
            }
            this.tempSecureStorage.delete();
            if (this.tempEquinoxDir != null) {
                try {
                    Files.walk(this.tempEquinoxDir.toPath()).sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
                } catch (IOException e) {
                    logger.log(Level.SEVERE, "Exception while deleting temp folder " + this.tempEquinoxDir, e);
                }
            }
            this.frameworkContext = null;
        }

    }


}
