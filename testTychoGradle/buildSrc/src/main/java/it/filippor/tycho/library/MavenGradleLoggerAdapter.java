package it.filippor.tycho.library;

import org.gradle.api.logging.Logger;

import org.eclipse.tycho.core.shared.MavenLogger;

public class MavenGradleLoggerAdapter implements MavenLogger {

    private Logger log;

    public MavenGradleLoggerAdapter(Logger log) {
        this.log = log;
    }

    @Override
    public void error(String message) {
        this.log.error(message);
    }

    @Override
    public void warn(String message) {
        this.log.warn(message);
    }

    @Override
    public void warn(String message, Throwable cause) {
        this.log.warn( message,cause);
    }

    @Override
    public void info(String message) {
        this.log.info( message);
    }

    @Override
    public void debug(String message) {
        this.log.debug(message);
    }

    @Override
    public boolean isDebugEnabled() {
        return this.log.isDebugEnabled();
    }

    @Override
    public boolean isExtendedDebugEnabled() {
        return this.log.isTraceEnabled();
    }

}
