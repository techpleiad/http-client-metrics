package org.springframework.cloud.commons.httpclient;

import io.micrometer.core.instrument.MeterRegistry;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.http.config.Registry;
import org.apache.http.config.RegistryBuilder;
import org.apache.http.conn.DnsResolver;
import org.apache.http.conn.HttpClientConnectionManager;
import org.apache.http.conn.HttpConnectionFactory;
import org.apache.http.conn.SchemePortResolver;
import org.apache.http.conn.socket.ConnectionSocketFactory;
import org.apache.http.conn.socket.PlainConnectionSocketFactory;
import org.apache.http.conn.ssl.NoopHostnameVerifier;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.impl.conn.CustomPoolingHttpClientConnectionManager;

import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.concurrent.TimeUnit;

/**
 * User: rajeshgupta
 * Date: 30/12/20
 */
public class CustomDefaultApacheHttpClientConnectionManagerFactory extends DefaultApacheHttpClientConnectionManagerFactory {

    private static final Log LOG = LogFactory.getLog(CustomDefaultApacheHttpClientConnectionManagerFactory.class);
    private MeterRegistry meterRegistry;

    public CustomDefaultApacheHttpClientConnectionManagerFactory() {
        super();
    }

    public CustomDefaultApacheHttpClientConnectionManagerFactory(final MeterRegistry meterRegistry) {
        super();
        this.meterRegistry = meterRegistry;
    }

    @Override
    public HttpClientConnectionManager newConnectionManager(final boolean disableSslValidation, final int maxTotalConnections, final int maxConnectionsPerRoute, final long timeToLive, final TimeUnit timeUnit, RegistryBuilder registryBuilder) {
        if (registryBuilder == null) {
            registryBuilder = RegistryBuilder.create().register("http", PlainConnectionSocketFactory.INSTANCE);
        }

        if (disableSslValidation) {
            try {
                final SSLContext sslContext = SSLContext.getInstance("SSL");
                sslContext
                        .init((KeyManager[]) null, new TrustManager[]{new DefaultApacheHttpClientConnectionManagerFactory.DisabledValidationTrustManager()}, new SecureRandom());
                registryBuilder
                        .register("https", new SSLConnectionSocketFactory(sslContext, NoopHostnameVerifier.INSTANCE));
            } catch (final NoSuchAlgorithmException var10) {
                LOG.warn("Error creating SSLContext", var10);
            } catch (final KeyManagementException var11) {
                LOG.warn("Error creating SSLContext", var11);
            }
        } else {
            registryBuilder.register("https", SSLConnectionSocketFactory.getSocketFactory());
        }

        final Registry<ConnectionSocketFactory> registry = registryBuilder.build();
        final CustomPoolingHttpClientConnectionManager connectionManager = new CustomPoolingHttpClientConnectionManager(registry, (HttpConnectionFactory) null, (SchemePortResolver) null, (DnsResolver) null, timeToLive, timeUnit, this.meterRegistry);
        connectionManager.setMaxTotal(maxTotalConnections);
        connectionManager.setDefaultMaxPerRoute(maxConnectionsPerRoute);
        return connectionManager;
    }
}
