package it.filippor.tycho.library.lock;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.FileLock;

import javax.swing.plaf.basic.BasicTreeUI.TreeHomeAction;

import org.eclipse.tycho.locking.facade.FileLocker;
import org.eclipse.tycho.locking.facade.LockTimeoutException;

class FileLockerImpl implements FileLocker {

    private static final String LOCKFILE_SUFFIX = ".tycholock";

    final File lockFile;
    FileOutputStream fos;
    FileLock lock;

    FileLockerImpl(File file)  {
        try {
        if (file.isDirectory()) {
                this.lockFile = new File(file, LOCKFILE_SUFFIX).getCanonicalFile();
        } else {
            this.lockFile = new File(file.getParentFile(), file.getName() + LOCKFILE_SUFFIX).getCanonicalFile();
        }
        if (this.lockFile.isDirectory()) {
            throw new RuntimeException("Lock marker file " + this.lockFile + " already exists and is a directory");
        }
        File parentDir = this.lockFile.getParentFile();
        if (!parentDir.isDirectory() && !parentDir.mkdirs()) {
            throw new RuntimeException("Could not create parent directory " + parentDir + " of lock marker file");
        }
        } catch (IOException e) {
            throw new RuntimeException("Could not create lock for " + file ,e);
        }
    }

    @Override
    public void lock() throws LockTimeoutException {
        try {
            if (!this.lockFile.exists()) {
                this.lockFile.getParentFile().mkdirs();
                this.lockFile.createNewFile();
            }
            this.fos = new FileOutputStream(this.lockFile);
            this.lock = this.fos.getChannel().lock();
        } catch (IOException e) {
            throw new IllegalArgumentException("cannot create file", e);
        }
    }

    @Override
    public void lock(long timeout) throws LockTimeoutException {
        try {
            if (!this.lockFile.exists()) {
                this.lockFile.getParentFile().mkdirs();
                this.lockFile.createNewFile();
            }
            this.fos = new FileOutputStream(this.lockFile);
        } catch (IOException e) {
            throw new IllegalArgumentException("cannot create file", e);
        }

        if (timeout < 0) {
            throw new IllegalArgumentException("timeout must not be negative");
        }
        final long waitInterval = 50L;
        long maxTries = (timeout / waitInterval) + 1;
        IOException ioException = null;
        for (long i = 0; i < maxTries; i++) {
            ioException = null;
            try {
                this.lock = this.fos.getChannel().tryLock();
            } catch (IOException ioe) {
                // keep trying (and re-throw eventually)
                ioException = ioe;
            }
            if (isLocked()) {
                return;
            }
            try {
                Thread.sleep(waitInterval);
            } catch (InterruptedException e) {
                // ignore
            }
        }
        String message = "lock timeout: Could not acquire lock on file " + this.lockFile + " for " + timeout + " msec";
        if (ioException != null) {
            throw new LockTimeoutException(message, ioException);
        } else {
            throw new LockTimeoutException(message);
        }

    }

    @Override
    public void release() {
        try {
            this.lock.release();
            this.fos.close();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

    }

    @Override
    public boolean isLocked() {
        return this.lock != null && this.lock.isValid();
    }

}
