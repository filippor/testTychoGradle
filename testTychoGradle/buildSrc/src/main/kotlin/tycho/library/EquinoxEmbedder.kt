package it.filippor.tycho.library;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Dictionary;
import java.util.Hashtable;
import java.util.LinkedHashMap;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

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

class EquinoxEmbedder(
		val installationLocation: File,
		val bundleLocations: Set<File>,
		val extraSystemPackages: List<String>,
		val localRepo: File,
		val isOffline: Boolean,
		val mavenLogger: MavenLogger
) : AutoCloseable {


	val logger = Logger.getLogger(EquinoxEmbedder::class.qualifiedName)
	private var tempSecureStorage: File? = null
	private var tempEquinoxDir: File? = null
	private var frameworkContext: BundleContext? = null;

	private val platformProperties = LinkedHashMap<String, String>()
	private val repositorySettingsProvider = MavenRepositorySettingsProvider()
	private val mavenContext = MavenContextImpl(localRepo, isOffline, this.mavenLogger, Properties())
	private val fileLockService = FileLockServiceImpl();


	fun <T> getService(clazz: Class<T>, filter: String? = null): T {
		start();
		// TODO technically, we're leaking service references here
//		var serviceReferences:Collection<ServiceReference<T>>;
		try {
			val tmp = this.frameworkContext
			if (tmp == null)
				throw  IllegalStateException("framework not initialized");
			val serviceReferences = tmp.getServiceReferences(clazz, filter);
			if (serviceReferences == null || serviceReferences.isEmpty()) {
				val sb = StringBuilder();
				sb.append("Service is not registered class='").append(clazz).append("'");
				if (filter != null) {
					sb.append("filter='").append(filter).append("'");
				}
				throw  IllegalStateException(sb.toString());
			}

			return tmp.getService(serviceReferences.iterator().next());
		} catch (e: InvalidSyntaxException) {
			throw  IllegalArgumentException(e);
		}
	}

	fun doStart() {
		System.setProperty("osgi.framework.useSystemProperties", "false"); //$NON-NLS-1$ //$NON-NLS-2$

		// end init

		// File frameworkDir = new File("C:/Users/ics_f/test/repo");
		val frameworkLocation = this.installationLocation.getAbsolutePath();

		this.platformProperties.put("osgi.install.area", frameworkLocation);
		this.platformProperties.put("osgi.syspath", frameworkLocation + "/plugins");
		this.platformProperties.put("osgi.configuration.area", copyToTempFolder(File(this.installationLocation, "configuration")));

		val bundles = StringBuilder()
		addBundlesDir(bundles, File(this.installationLocation, "plugins").listFiles(), false);

		for (location in this.bundleLocations) {
			if (bundles.length > 0) {
				bundles.append(',');
			}
			bundles.append(getReferenceUrl(location));
		}
		val string = bundles.toString();
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

		registerService(MavenRepositorySettings::class.java, this.repositorySettingsProvider);
		registerService(MavenContext::class.java, this.mavenContext);
		registerService(FileLockService::class.java, this.fileLockService);
	}

	fun formatExtraSystemPackage(extraSystemPackages: List<String>): String {
		val sb = StringBuilder();
		for (pkg in extraSystemPackages) {
			if (sb.length > 0) {
				sb.append(',');
			}
			sb.append(pkg);
		}
		return sb.toString();
	}


	fun <T> registerService(clazz: Class<T>, service: T, properties: Dictionary<String, Any> = Hashtable<String, Any>(1)) {
		// don't need to call checkStarted here because EmbeddedEquinox instances are already
		// started
		this.frameworkContext?.registerService(clazz, service, properties);
	}

	private fun activateBundlesInWorkingOrder() {
		// activate bundles which need to do work in their respective activator; stick to a working
		// order (cf. bug 359787)
		// TODO this order should come from the EquinoxRuntimeLocator
		tryActivateBundle("org.eclipse.equinox.ds");
		tryActivateBundle("org.eclipse.equinox.registry");
		tryActivateBundle("org.eclipse.core.net");
	}

	private fun tryActivateBundle(symbolicName: String) {
		for (bundle in this.frameworkContext?.getBundles() ?: emptyArray<Bundle>()) {
			if (symbolicName.equals(bundle.getSymbolicName())) {
				try {
					bundle.start(Bundle.START_TRANSIENT); // don't have OSGi remember the autostart
					// setting; want to start these bundles
					// manually to control the start order
				} catch (e: BundleException) {
					logger.log(Level.WARNING, "Could not start bundle " + bundle.getSymbolicName(), e);
				}
			}
		}
	}

	private fun getNonFrameworkArgs(): Array<String> {
		try {
			val tmp = File.createTempFile("tycho", "secure_storage")
			this.tempSecureStorage = tmp;
			tmp.deleteOnExit();


			val nonFrameworkArgs = ArrayList<String>();
			nonFrameworkArgs.add("-eclipse.keyring");
			nonFrameworkArgs.add(tmp.getAbsolutePath());
			// TODO nonFrameworkArgs.add("-eclipse.password");
			if (this.mavenLogger.isDebugEnabled()) {
				nonFrameworkArgs.add("-debug");
				nonFrameworkArgs.add("-consoleLog");
			}
			return Array<String>(nonFrameworkArgs.size, { i -> nonFrameworkArgs[i] })
		} catch (e: IOException) {
			throw  RuntimeException("Could not create Tycho secure store file in temp dir " + System.getProperty("java.io.tmpdir"), e);
		}
	}

	private fun copyToTempFolder(configDir: File): String {
		val equinoxTmp = File.createTempFile("tycho", "equinox");
		if (!(equinoxTmp.delete() && equinoxTmp.mkdirs())) {
			throw IOException("Could not create temp dir " + equinoxTmp);
		}
		val tempConfigDir = File(equinoxTmp, "config");
		if (!(tempConfigDir.mkdirs())) {
			throw  IOException("Could not create temp config dir " + tempConfigDir);
		}
		Files.copy(configDir.toPath().resolve("config.ini"), tempConfigDir.toPath().resolve("config.ini"));
		// FileUtils.copyFileToDirectory(new File(configDir, "config.ini"), tempConfigDir);
		this.tempEquinoxDir = equinoxTmp;
		return tempConfigDir.getAbsolutePath();
	}

	private fun addBundlesDir(bundles: StringBuilder, files: Array<File>, absolute: Boolean) {
		for (file in files) {
			if (isFrameworkBundle(file)) continue;
			if (bundles.length > 0) bundles.append(',');
			if (absolute) {
				bundles.append(getReferenceUrl(file));
			} else {
				val name = file.getName();
				val verIdx = name.indexOf('_');
				if (verIdx > 0) {
					bundles.append(name.substring(0, verIdx));
				} else {
					throw  RuntimeException("File name doesn't match expected pattern: " + file);
				}
			}
		}
	}


	public fun start() {
		if (this.frameworkContext != null) {
			return;
		}

//        if ("Eclipse".equals(System.getProperty("org.osgi.framework.vendor"))) {
//            throw new IllegalStateException(
//                    "Cannot run multiple Equinox instances in one build. Consider configuring the Tycho build extension, so that all mojos using Tycho functionality share the same Equinox runtime.");
//        }

		// https://bugs.eclipse.org/bugs/show_bug.cgi?id=308949
		// restore TCCL to make sure equinox classloader does not leak into our clients
		var tccl = Thread.currentThread().getContextClassLoader();
		try {
			doStart();
		} finally {
			Thread.currentThread().setContextClassLoader(tccl);
		}
	}

	protected fun isFrameworkBundle(file: File) = file.getName().startsWith("org.eclipse.osgi_");


	// TODO replace this by URI.toString once Eclipse bug #328926 is resolved
	fun getReferenceUrl(file: File) = "reference:" + "file:" + file.getAbsoluteFile().toURI().normalize().getPath();


	override fun close() {
		if (this.frameworkContext != null) {
			try {
				EclipseStarter.shutdown();
			} catch (e: Exception) {
				logger.log(Level.SEVERE, "Exception while shutting down equinox", e);
			}
			this.tempSecureStorage?.delete();
			try {
				tempEquinoxDir?.deleteRecursively();
			} catch (e: IOException) {
				logger.log(Level.SEVERE, "Exception while deleting temp folder " + this.tempEquinoxDir, e);
			}
			this.frameworkContext = null;
		}

	}


}
