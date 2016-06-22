package com.unboundid.ldap;

import com.unboundid.ldap.listener.InMemoryDirectoryServer;
import com.unboundid.ldap.listener.InMemoryDirectoryServerConfig;
import com.unboundid.ldap.listener.InMemoryListenerConfig;
import com.unboundid.ldap.sdk.LDAPConnection;
import com.unboundid.ldap.sdk.LDAPException;
import com.unboundid.ldap.sdk.schema.Schema;
import com.unboundid.util.ssl.KeyStoreKeyManager;
import com.unboundid.util.ssl.SSLUtil;
import com.unboundid.util.ssl.TrustStoreTrustManager;
import org.apache.commons.io.IOUtils;
import org.ldaptive.LdapEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;

import javax.annotation.PreDestroy;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Collection;
import java.util.Properties;

/**
 * The {@link LdapServer} is responsible for...
 *
 * @author Misagh Moayyed
 */
public final class LdapServer implements Closeable {
    private static final Logger LOGGER = LoggerFactory.getLogger(LdapServer.class);

    private final InMemoryDirectoryServer directoryServer;

    private Collection<LdapEntry> ldapEntries;

    /**
     * Instantiates a new Ldap directory server.
     * Parameters need to be streams so they can be read from JARs.
     */
    public LdapServer(final InputStream properties,
                      final InputStream ldifFile,
                      final InputStream schemaFile) {
        try {
            final Properties p = new Properties();
            p.load(properties);

            final InMemoryDirectoryServerConfig config =
                    new InMemoryDirectoryServerConfig(p.getProperty("ldap.rootDn"));
            config.addAdditionalBindCredentials(p.getProperty("ldap.managerDn"), p.getProperty("ldap.managerPassword"));

            final File keystoreFile = File.createTempFile("key", "store");
            try (final OutputStream outputStream = new FileOutputStream(keystoreFile)) {
                IOUtils.copy(new ClassPathResource("/ldapServerTrustStore").getInputStream(), outputStream);
            }

            final String serverKeyStorePath = keystoreFile.getCanonicalPath();
            final SSLUtil serverSSLUtil = new SSLUtil(
                    new KeyStoreKeyManager(serverKeyStorePath, "changeit".toCharArray()), new TrustStoreTrustManager(serverKeyStorePath));
            final SSLUtil clientSSLUtil = new SSLUtil(new TrustStoreTrustManager(serverKeyStorePath));
            config.setListenerConfigs(
                    InMemoryListenerConfig.createLDAPConfig("LDAP", // Listener name
                            null, // Listen address. (null = listen on all interfaces)
                            1389, // Listen port (0 = automatically choose an available port)
                            serverSSLUtil.createSSLSocketFactory()), // StartTLS factory
                    InMemoryListenerConfig.createLDAPSConfig("LDAPS", // Listener name
                            null, // Listen address. (null = listen on all interfaces)
                            1636, // Listen port (0 = automatically choose an available port)
                            serverSSLUtil.createSSLServerSocketFactory(), // Server factory
                            clientSSLUtil.createSSLSocketFactory())); // Client factory

            config.setEnforceSingleStructuralObjectClass(false);
            config.setEnforceAttributeSyntaxCompliance(true);
            config.setMaxConnections(-1);

            final File file = File.createTempFile("ldap", "schema");
            try (final OutputStream outputStream = new FileOutputStream(file)) {
                IOUtils.copy(schemaFile, outputStream);
            }

            final Schema s = Schema.mergeSchemas(Schema.getSchema(file));
            config.setSchema(s);


            this.directoryServer = new InMemoryDirectoryServer(config);
            LOGGER.debug("Populating directory...");

            final File ldif = File.createTempFile("ldiff", "file");
            try (final OutputStream outputStream = new FileOutputStream(ldif)) {
                IOUtils.copy(ldifFile, outputStream);
            }

            this.directoryServer.importFromLDIF(true, ldif.getCanonicalPath());
            this.directoryServer.restartServer();

            final LDAPConnection c = getConnection();
            LOGGER.debug("Connected to {}:{}", c.getConnectedAddress(), c.getConnectedPort());

            populateDefaultEntries(c);

            c.close();
        } catch (final Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Instantiates a new Ldap directory server.
     */
    public LdapServer(final File properties, final File ldifFile, final File... schemaFile)
            throws FileNotFoundException {
        this(new FileInputStream(properties),
                new FileInputStream(ldifFile),
                new FileInputStream(ldifFile));
    }

    private void populateDefaultEntries(final LDAPConnection c) throws Exception {
        populateEntries(c, new ClassPathResource("users-groups.ldif").getInputStream());
    }

    public void populateEntries(final InputStream rs) throws Exception {
        populateEntries(getConnection(), rs);
    }

    protected void populateEntries(final LDAPConnection c, final InputStream rs) throws Exception {
        this.ldapEntries = LdapTestUtils.readLdif(rs, getBaseDn());
        LdapTestUtils.createLdapEntries(c, ldapEntries);
        populateEntriesInternal(c);
    }

    protected void populateEntriesInternal(final LDAPConnection c) {
    }

    public String getBaseDn() {
        return this.directoryServer.getBaseDNs().get(0).toNormalizedString();
    }

    public Collection<LdapEntry> getLdapEntries() {
        return this.ldapEntries;
    }

    public LDAPConnection getConnection() throws LDAPException {
        return this.directoryServer.getConnection();
    }

    @Override
    @PreDestroy
    public void close() throws IOException {
        LOGGER.debug("Shutting down LDAP server...");
        this.directoryServer.shutDown(true);
        LOGGER.debug("Shut down LDAP server.");
    }

    public static void main(final String[] args) throws Exception {
        try {
            final Thread th = new Thread(() -> {
                try {
                    final LdapServer server =
                            new LdapServer(new ClassPathResource("ldap.properties").getInputStream(),
                                    new ClassPathResource("ldap-base.ldif").getInputStream(),
                                    new ClassPathResource("standard-ldap.schema").getInputStream());
                    LOGGER.info("Connected to baseDn {}", server.getBaseDn());
                    LOGGER.info("{} ldap entries are available", server.getLdapEntries().size());
                } catch (final Throwable e) {
                    LOGGER.error(e.getMessage(), e);
                }

            });
            th.setDaemon(true);
            th.start();
            while (true) {
            }
        } catch (final Throwable e) {
            LOGGER.error(e.getMessage(), e);
        }
        LOGGER.debug("Going away. Bye!");
    }
}
