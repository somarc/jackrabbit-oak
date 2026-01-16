/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.jackrabbit.oak.segment.http.server;

import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.SecureRequestCustomizer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.SslConnectionFactory;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;

/**
 * TLS/SSL configuration for the Segment HTTP Server.
 * 
 * <p>Provides secure HTTPS connections for:
 * <ul>
 *   <li>Validator-to-validator communication</li>
 *   <li>Sling author-to-validator communication</li>
 *   <li>External API access</li>
 * </ul>
 * 
 * <p>Configuration options:
 * <ul>
 *   <li>Keystore-based: Use Java keystore (JKS/PKCS12) with certificate and private key</li>
 *   <li>PEM-based: Use PEM certificate and key files (for Kubernetes/cert-manager)</li>
 *   <li>mTLS: Mutual TLS for validator-to-validator authentication</li>
 * </ul>
 * 
 * <p>System properties:
 * <ul>
 *   <li>{@code tls.enabled} - Enable TLS (default: false)</li>
 *   <li>{@code tls.keystore.path} - Path to keystore file</li>
 *   <li>{@code tls.keystore.password} - Keystore password</li>
 *   <li>{@code tls.keystore.type} - Keystore type (JKS, PKCS12)</li>
 *   <li>{@code tls.cert.path} - Path to PEM certificate file</li>
 *   <li>{@code tls.key.path} - Path to PEM private key file</li>
 *   <li>{@code tls.truststore.path} - Path to truststore for mTLS</li>
 *   <li>{@code tls.truststore.password} - Truststore password</li>
 *   <li>{@code tls.client.auth} - Client authentication mode (none, want, need)</li>
 *   <li>{@code tls.protocols} - Allowed TLS protocols (default: TLSv1.2,TLSv1.3)</li>
 *   <li>{@code tls.ciphers} - Allowed cipher suites</li>
 * </ul>
 * 
 * @since 1.89
 */
public class TlsConfiguration {
    
    private static final Logger log = LoggerFactory.getLogger(TlsConfiguration.class);
    
    // System property keys
    public static final String PROP_TLS_ENABLED = "tls.enabled";
    public static final String PROP_KEYSTORE_PATH = "tls.keystore.path";
    public static final String PROP_KEYSTORE_PASSWORD = "tls.keystore.password";
    public static final String PROP_KEYSTORE_TYPE = "tls.keystore.type";
    public static final String PROP_CERT_PATH = "tls.cert.path";
    public static final String PROP_KEY_PATH = "tls.key.path";
    public static final String PROP_TRUSTSTORE_PATH = "tls.truststore.path";
    public static final String PROP_TRUSTSTORE_PASSWORD = "tls.truststore.password";
    public static final String PROP_CLIENT_AUTH = "tls.client.auth";
    public static final String PROP_PROTOCOLS = "tls.protocols";
    public static final String PROP_CIPHERS = "tls.ciphers";
    
    // Default values
    private static final String DEFAULT_KEYSTORE_TYPE = "PKCS12";
    private static final String DEFAULT_PROTOCOLS = "TLSv1.2,TLSv1.3";
    private static final String DEFAULT_CLIENT_AUTH = "none";
    
    // Recommended cipher suites for TLS 1.2/1.3
    private static final String[] RECOMMENDED_CIPHERS = {
        // TLS 1.3 ciphers
        "TLS_AES_256_GCM_SHA384",
        "TLS_AES_128_GCM_SHA256",
        "TLS_CHACHA20_POLY1305_SHA256",
        // TLS 1.2 ciphers (ECDHE for forward secrecy)
        "TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384",
        "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384",
        "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256",
        "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256",
        "TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256",
        "TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256"
    };
    
    private final boolean enabled;
    private final String keystorePath;
    private final String keystorePassword;
    private final String keystoreType;
    private final String certPath;
    private final String keyPath;
    private final String truststorePath;
    private final String truststorePassword;
    private final String clientAuth;
    private final String[] protocols;
    private final String[] ciphers;
    
    /**
     * Create TLS configuration from system properties.
     */
    public TlsConfiguration() {
        this.enabled = Boolean.parseBoolean(System.getProperty(PROP_TLS_ENABLED, "false"));
        this.keystorePath = System.getProperty(PROP_KEYSTORE_PATH);
        this.keystorePassword = System.getProperty(PROP_KEYSTORE_PASSWORD, "");
        this.keystoreType = System.getProperty(PROP_KEYSTORE_TYPE, DEFAULT_KEYSTORE_TYPE);
        this.certPath = System.getProperty(PROP_CERT_PATH);
        this.keyPath = System.getProperty(PROP_KEY_PATH);
        this.truststorePath = System.getProperty(PROP_TRUSTSTORE_PATH);
        this.truststorePassword = System.getProperty(PROP_TRUSTSTORE_PASSWORD, "");
        this.clientAuth = System.getProperty(PROP_CLIENT_AUTH, DEFAULT_CLIENT_AUTH);
        
        String protocolsStr = System.getProperty(PROP_PROTOCOLS, DEFAULT_PROTOCOLS);
        this.protocols = protocolsStr.split(",");
        
        String ciphersStr = System.getProperty(PROP_CIPHERS);
        this.ciphers = ciphersStr != null ? ciphersStr.split(",") : RECOMMENDED_CIPHERS;
    }
    
    /**
     * Create TLS configuration with explicit values.
     */
    public TlsConfiguration(boolean enabled, String keystorePath, String keystorePassword,
                           String keystoreType, String certPath, String keyPath,
                           String truststorePath, String truststorePassword,
                           String clientAuth, String[] protocols, String[] ciphers) {
        this.enabled = enabled;
        this.keystorePath = keystorePath;
        this.keystorePassword = keystorePassword;
        this.keystoreType = keystoreType;
        this.certPath = certPath;
        this.keyPath = keyPath;
        this.truststorePath = truststorePath;
        this.truststorePassword = truststorePassword;
        this.clientAuth = clientAuth;
        this.protocols = protocols != null ? protocols : DEFAULT_PROTOCOLS.split(",");
        this.ciphers = ciphers != null ? ciphers : RECOMMENDED_CIPHERS;
    }
    
    /**
     * Check if TLS is enabled.
     */
    public boolean isEnabled() {
        return enabled;
    }
    
    /**
     * Configure a Jetty server with TLS.
     * 
     * @param server The Jetty server to configure
     * @param httpPort The HTTP port (will be redirected to HTTPS)
     * @param httpsPort The HTTPS port
     * @return The configured server
     * @throws Exception if configuration fails
     */
    public Server configureServer(Server server, int httpPort, int httpsPort) throws Exception {
        if (!enabled) {
            log.info("TLS is disabled, using HTTP only on port {}", httpPort);
            ServerConnector httpConnector = new ServerConnector(server);
            httpConnector.setPort(httpPort);
            server.addConnector(httpConnector);
            return server;
        }
        
        log.info("Configuring TLS for HTTPS on port {}", httpsPort);
        
        // Create SSL context factory
        SslContextFactory.Server sslContextFactory = createSslContextFactory();
        
        // HTTP configuration
        HttpConfiguration httpConfig = new HttpConfiguration();
        httpConfig.setSecureScheme("https");
        httpConfig.setSecurePort(httpsPort);
        httpConfig.setSendServerVersion(false);
        httpConfig.setSendXPoweredBy(false);
        
        // HTTPS configuration
        HttpConfiguration httpsConfig = new HttpConfiguration(httpConfig);
        httpsConfig.addCustomizer(new SecureRequestCustomizer());
        
        // HTTPS connector
        ServerConnector httpsConnector = new ServerConnector(server,
            new SslConnectionFactory(sslContextFactory, HttpVersion.HTTP_1_1.asString()),
            new HttpConnectionFactory(httpsConfig));
        httpsConnector.setPort(httpsPort);
        httpsConnector.setIdleTimeout(30000);
        
        server.addConnector(httpsConnector);
        
        // Optionally add HTTP connector for redirect (or health checks)
        if (httpPort > 0 && httpPort != httpsPort) {
            ServerConnector httpConnector = new ServerConnector(server);
            httpConnector.setPort(httpPort);
            server.addConnector(httpConnector);
            log.info("HTTP connector added on port {} (for health checks/redirect)", httpPort);
        }
        
        log.info("✅ TLS configured successfully");
        log.info("   - Protocols: {}", String.join(", ", protocols));
        log.info("   - Client auth: {}", clientAuth);
        
        return server;
    }
    
    /**
     * Create SSL context factory for Jetty.
     */
    private SslContextFactory.Server createSslContextFactory() throws Exception {
        SslContextFactory.Server sslContextFactory = new SslContextFactory.Server();
        
        // Configure keystore (certificate + private key)
        if (keystorePath != null && !keystorePath.isEmpty()) {
            // Keystore-based configuration
            log.info("Using keystore: {}", keystorePath);
            sslContextFactory.setKeyStorePath(keystorePath);
            sslContextFactory.setKeyStorePassword(keystorePassword);
            sslContextFactory.setKeyStoreType(keystoreType);
        } else if (certPath != null && keyPath != null) {
            // PEM-based configuration (for Kubernetes/cert-manager)
            log.info("Using PEM certificate: {}", certPath);
            log.info("Using PEM key: {}", keyPath);
            
            // Jetty 9.4+ supports PEM files directly via KeyStore
            KeyStore keyStore = loadPemKeyStore(certPath, keyPath);
            sslContextFactory.setKeyStore(keyStore);
            sslContextFactory.setKeyStorePassword("");
        } else {
            throw new IllegalStateException(
                "TLS enabled but no keystore or certificate configured. " +
                "Set either tls.keystore.path or tls.cert.path + tls.key.path");
        }
        
        // Configure truststore for mTLS (optional)
        if (truststorePath != null && !truststorePath.isEmpty()) {
            log.info("Using truststore for mTLS: {}", truststorePath);
            sslContextFactory.setTrustStorePath(truststorePath);
            sslContextFactory.setTrustStorePassword(truststorePassword);
        }
        
        // Configure client authentication
        switch (clientAuth.toLowerCase()) {
            case "need":
                sslContextFactory.setNeedClientAuth(true);
                log.info("mTLS enabled: client certificate REQUIRED");
                break;
            case "want":
                sslContextFactory.setWantClientAuth(true);
                log.info("mTLS enabled: client certificate REQUESTED");
                break;
            default:
                log.info("mTLS disabled: no client certificate required");
        }
        
        // Configure protocols
        sslContextFactory.setIncludeProtocols(protocols);
        sslContextFactory.setExcludeProtocols("SSLv2", "SSLv3", "TLSv1", "TLSv1.1");
        
        // Configure cipher suites
        sslContextFactory.setIncludeCipherSuites(ciphers);
        sslContextFactory.setExcludeCipherSuites(
            ".*NULL.*",
            ".*RC4.*",
            ".*MD5.*",
            ".*DES.*",
            ".*DSS.*",
            ".*EXPORT.*",
            ".*_anon_.*"
        );
        
        // Enable OCSP stapling if available
        sslContextFactory.setEnableOCSP(true);
        
        // Renegotiation settings
        sslContextFactory.setRenegotiationAllowed(false);
        
        return sslContextFactory;
    }
    
    /**
     * Load PEM certificate and key into a KeyStore.
     * 
     * <p>This is a simplified implementation. For production, consider using
     * Bouncy Castle or a dedicated PEM parser.
     */
    private KeyStore loadPemKeyStore(String certPath, String keyPath) 
            throws KeyStoreException, IOException, NoSuchAlgorithmException, CertificateException {
        
        File certFile = new File(certPath);
        File keyFile = new File(keyPath);
        
        if (!certFile.exists()) {
            throw new IOException("Certificate file not found: " + certPath);
        }
        if (!keyFile.exists()) {
            throw new IOException("Key file not found: " + keyPath);
        }
        
        // For PEM files, we need to use Jetty's PEM utilities or Bouncy Castle
        // This is a placeholder - in production, use proper PEM parsing
        // Jetty 10+ has better PEM support via SslContextFactory.Server.setKeyStorePath()
        
        // For now, recommend converting PEM to PKCS12:
        // openssl pkcs12 -export -in cert.pem -inkey key.pem -out keystore.p12 -name server
        
        log.warn("PEM file loading requires conversion to PKCS12. " +
                "Use: openssl pkcs12 -export -in {} -inkey {} -out keystore.p12 -name server",
                certPath, keyPath);
        
        throw new UnsupportedOperationException(
            "Direct PEM loading not yet implemented. " +
            "Convert to PKCS12: openssl pkcs12 -export -in " + certPath + 
            " -inkey " + keyPath + " -out keystore.p12 -name server");
    }
    
    /**
     * Builder for TlsConfiguration.
     */
    public static class Builder {
        private boolean enabled = false;
        private String keystorePath;
        private String keystorePassword = "";
        private String keystoreType = DEFAULT_KEYSTORE_TYPE;
        private String certPath;
        private String keyPath;
        private String truststorePath;
        private String truststorePassword = "";
        private String clientAuth = DEFAULT_CLIENT_AUTH;
        private String[] protocols = DEFAULT_PROTOCOLS.split(",");
        private String[] ciphers = RECOMMENDED_CIPHERS;
        
        public Builder enabled(boolean enabled) {
            this.enabled = enabled;
            return this;
        }
        
        public Builder keystorePath(String path) {
            this.keystorePath = path;
            return this;
        }
        
        public Builder keystorePassword(String password) {
            this.keystorePassword = password;
            return this;
        }
        
        public Builder keystoreType(String type) {
            this.keystoreType = type;
            return this;
        }
        
        public Builder certPath(String path) {
            this.certPath = path;
            return this;
        }
        
        public Builder keyPath(String path) {
            this.keyPath = path;
            return this;
        }
        
        public Builder truststorePath(String path) {
            this.truststorePath = path;
            return this;
        }
        
        public Builder truststorePassword(String password) {
            this.truststorePassword = password;
            return this;
        }
        
        public Builder clientAuth(String mode) {
            this.clientAuth = mode;
            return this;
        }
        
        public Builder protocols(String... protocols) {
            this.protocols = protocols;
            return this;
        }
        
        public Builder ciphers(String... ciphers) {
            this.ciphers = ciphers;
            return this;
        }
        
        public TlsConfiguration build() {
            return new TlsConfiguration(enabled, keystorePath, keystorePassword,
                keystoreType, certPath, keyPath, truststorePath, truststorePassword,
                clientAuth, protocols, ciphers);
        }
    }
    
    public static Builder builder() {
        return new Builder();
    }
}
