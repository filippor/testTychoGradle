package it.filippor.tycho.library;

import org.eclipse.tycho.core.resolver.shared.MavenRepositoryLocation;
import org.eclipse.tycho.core.resolver.shared.MavenRepositorySettings;

public class MavenRepositorySettingsProvider implements MavenRepositorySettings {

    @Override
    public MavenRepositoryLocation getMirror(MavenRepositoryLocation location) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public Credentials getCredentials(MavenRepositoryLocation location) {
        // TODO Auto-generated method stub
        return null;
    }

}
