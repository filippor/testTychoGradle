package it.filippor.tycho.library.lock;

import java.io.File;

import org.eclipse.tycho.locking.facade.FileLockService;
import org.eclipse.tycho.locking.facade.FileLocker;


public class FileLockServiceImpl implements FileLockService {

    @Override
    public FileLocker getFileLocker(File file) {
        return new FileLockerImpl(file);
    }
}
