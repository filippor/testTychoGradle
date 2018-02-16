package it.filippor.tycho.library;

import org.gradle.api.logging.Logger;

import org.eclipse.tycho.core.shared.MavenLogger;
import org.eclipse.tycho.core.resolver.shared.MavenRepositorySettings
import org.eclipse.tycho.core.resolver.shared.MavenRepositoryLocation
import org.eclipse.tycho.locking.facade.FileLocker
import org.eclipse.tycho.locking.facade.FileLockService
import java.io.File
import org.gradle.cache.FileLockManager.LockMode
import org.gradle.cache.FileLockManager
import java.io.FileOutputStream
import java.nio.channels.FileLock
import java.io.IOException
import org.eclipse.tycho.locking.facade.LockTimeoutException

class MavenGradleLoggerAdapter(var log: Logger) : MavenLogger {
	override fun warn(m: String?) {
		log.warn(m)
	}

	override fun warn(m: String?, cause: Throwable?) {
		log.warn(m, cause)
	}

	override fun info(m: String?) {
		log.info(m)
	}

	override fun isExtendedDebugEnabled() = log.isTraceEnabled

	override fun error(m: String?) {
		log.error(m)
	}

	override fun isDebugEnabled() = log.isDebugEnabled


	override fun debug(m: String?) {
		log.debug(m)
	}
}

class MavenRepositorySettingsProvider : MavenRepositorySettings {
	override fun getMirror(location: MavenRepositoryLocation?): MavenRepositoryLocation? = null
	override fun getCredentials(location: MavenRepositoryLocation?): MavenRepositorySettings.Credentials? = null
}

class FileLockServiceImpl : FileLockService {
	override fun getFileLocker(file: File): FileLocker = FileLockerImpl(file)

}

class FileLockerImpl(val file: File) : FileLocker {
	var lockFile: File
	var fos: FileOutputStream? = null
	var lock: FileLock? = null

	companion object {
		val LOCKFILE_SUFFIX = ".tycholock"
	}

	init {
		try {
			this.lockFile =
					if (file.isDirectory()) {
						File(file, LOCKFILE_SUFFIX).getCanonicalFile();
					} else {
						File(file.getParentFile(), file.getName() + LOCKFILE_SUFFIX).getCanonicalFile();
					}
			if (this.lockFile.isDirectory()) {
				throw  RuntimeException("Lock marker file " + this.lockFile + " already exists and is a directory");
			}
			val parentDir = this.lockFile.getParentFile();
			if (!parentDir.isDirectory() && !parentDir.mkdirs()) {
				throw  RuntimeException("Could not create parent directory " + parentDir + " of lock marker file");
			}
		} catch (e: IOException) {
			throw  RuntimeException("Could not create lock for " + file, e);
		}
	}

	override fun lock() {
		try {
			if (!this.lockFile.exists()) {
				this.lockFile.getParentFile().mkdirs();
				this.lockFile.createNewFile();
			}
			var tmp  = FileOutputStream (this.lockFile);
			fos = tmp
			this.lock = tmp.getChannel().lock();
		} catch ( e:IOException) {
			throw IllegalArgumentException ("cannot create file", e);
		}
	}

	override fun lock(timeout: Long) {
		var tmp:FileOutputStream
		try {
            if (!this.lockFile.exists()) {
                this.lockFile.getParentFile().mkdirs();
                this.lockFile.createNewFile();
            }
            tmp=  FileOutputStream(this.lockFile)
			fos=tmp
        } catch (e:IOException ) {
            throw IllegalArgumentException("cannot create file", e);
        }

        if (timeout < 0) {
            throw  IllegalArgumentException("timeout must not be negative");
        }
        val waitInterval = 50L;
        val maxTries = (timeout / waitInterval) + 1;
        var ioException:IOException? = null;
        for ( i in 0..maxTries) {
            ioException = null;
            try {
                lock = tmp.getChannel().tryLock();
            } catch ( ioe:IOException) {
                // keep trying (and re-throw eventually)
                ioException = ioe;
            }
            if (isLocked()) {
                return;
            }
            try {
                Thread.sleep(waitInterval);
            } catch ( e:InterruptedException) {
                // ignore
            }
        }
        val message = "lock timeout: Could not acquire lock on file " + this.lockFile + " for " + timeout + " msec";
        if (ioException != null) {
            throw LockTimeoutException(message, ioException);
        } else {
            throw LockTimeoutException(message);
        }
	}

	override fun release() {
		 try {
            lock?.release();
            fos?.close();
        } catch ( e:IOException) {
            throw RuntimeException(e);
        }
	}

	override fun isLocked(): Boolean = lock?.isValid ?: false

}