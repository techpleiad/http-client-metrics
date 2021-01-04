package org.apache.http.impl.conn;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.http.HttpClientConnection;
import org.apache.http.HttpHost;
import org.apache.http.config.ConnectionConfig;
import org.apache.http.config.Registry;
import org.apache.http.config.RegistryBuilder;
import org.apache.http.config.SocketConfig;
import org.apache.http.conn.ConnectionPoolTimeoutException;
import org.apache.http.conn.ConnectionRequest;
import org.apache.http.conn.DnsResolver;
import org.apache.http.conn.HttpClientConnectionManager;
import org.apache.http.conn.HttpClientConnectionOperator;
import org.apache.http.conn.HttpConnectionFactory;
import org.apache.http.conn.ManagedHttpClientConnection;
import org.apache.http.conn.SchemePortResolver;
import org.apache.http.conn.routing.HttpRoute;
import org.apache.http.conn.socket.ConnectionSocketFactory;
import org.apache.http.conn.socket.PlainConnectionSocketFactory;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.pool.ConnFactory;
import org.apache.http.pool.ConnPoolControl;
import org.apache.http.pool.PoolEntry;
import org.apache.http.pool.PoolEntryCallback;
import org.apache.http.pool.PoolStats;
import org.apache.http.protocol.HttpContext;
import org.apache.http.util.Args;
import org.apache.http.util.Asserts;

import javax.annotation.PostConstruct;
import java.io.Closeable;
import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * User: rajeshgupta
 * Date: 30/12/20
 */
public class CustomPoolingHttpClientConnectionManager implements HttpClientConnectionManager, ConnPoolControl<HttpRoute>, Closeable {

    private final Log log = LogFactory.getLog(getClass());

    private final ConfigData configData;
    private final CustomObservableCPool pool;
    private final HttpClientConnectionOperator connectionOperator;
    private MeterRegistry meterRegistry;
    private final AtomicBoolean isShutDown;

    private static Registry<ConnectionSocketFactory> getDefaultRegistry() {
        return RegistryBuilder.<ConnectionSocketFactory>create()
                .register("http", PlainConnectionSocketFactory.getSocketFactory())
                .register("https", SSLConnectionSocketFactory.getSocketFactory())
                .build();
    }

    public CustomPoolingHttpClientConnectionManager() {
        this(getDefaultRegistry());
    }

    public CustomPoolingHttpClientConnectionManager(final long timeToLive, final TimeUnit timeUnit) {
        this(getDefaultRegistry(), null, null, null, timeToLive, timeUnit);
    }

    public CustomPoolingHttpClientConnectionManager(
            final Registry<ConnectionSocketFactory> socketFactoryRegistry) {
        this(socketFactoryRegistry, null, null);
    }

    public CustomPoolingHttpClientConnectionManager(
            final Registry<ConnectionSocketFactory> socketFactoryRegistry,
            final DnsResolver dnsResolver) {
        this(socketFactoryRegistry, null, dnsResolver);
    }

    public CustomPoolingHttpClientConnectionManager(
            final Registry<ConnectionSocketFactory> socketFactoryRegistry,
            final HttpConnectionFactory<HttpRoute, ManagedHttpClientConnection> connFactory) {
        this(socketFactoryRegistry, connFactory, null);
    }

    public CustomPoolingHttpClientConnectionManager(
            final HttpConnectionFactory<HttpRoute, ManagedHttpClientConnection> connFactory) {
        this(getDefaultRegistry(), connFactory, null);
    }

    public CustomPoolingHttpClientConnectionManager(
            final Registry<ConnectionSocketFactory> socketFactoryRegistry,
            final HttpConnectionFactory<HttpRoute, ManagedHttpClientConnection> connFactory,
            final DnsResolver dnsResolver) {
        this(socketFactoryRegistry, connFactory, null, dnsResolver, -1, TimeUnit.MILLISECONDS);
    }

    public CustomPoolingHttpClientConnectionManager(
            final Registry<ConnectionSocketFactory> socketFactoryRegistry,
            final HttpConnectionFactory<HttpRoute, ManagedHttpClientConnection> connFactory,
            final SchemePortResolver schemePortResolver,
            final DnsResolver dnsResolver,
            final long timeToLive, final TimeUnit timeUnit) {
        this(
                new DefaultHttpClientConnectionOperator(socketFactoryRegistry, schemePortResolver, dnsResolver),
                connFactory, null,
                timeToLive, timeUnit
        );
    }

    public CustomPoolingHttpClientConnectionManager(
            final Registry<ConnectionSocketFactory> socketFactoryRegistry,
            final HttpConnectionFactory<HttpRoute, ManagedHttpClientConnection> connFactory,
            final SchemePortResolver schemePortResolver,
            final DnsResolver dnsResolver,
            final long timeToLive, final TimeUnit timeUnit,
            final MeterRegistry meterRegistry) {
        this(
                new DefaultHttpClientConnectionOperator(socketFactoryRegistry, schemePortResolver, dnsResolver),
                connFactory, meterRegistry,
                timeToLive, timeUnit
        );
    }

    /**
     * @since 4.4
     */
    public CustomPoolingHttpClientConnectionManager(
            final HttpClientConnectionOperator httpClientConnectionOperator,
            final HttpConnectionFactory<HttpRoute, ManagedHttpClientConnection> connFactory,
            final MeterRegistry meterRegistry,
            final long timeToLive, final TimeUnit timeUnit) {
        super();
        this.configData = new CustomPoolingHttpClientConnectionManager.ConfigData();
        this.pool = new CustomObservableCPool(new CustomPoolingHttpClientConnectionManager.InternalConnectionFactory(this.configData,
                connFactory), meterRegistry, 2, 20, timeToLive, timeUnit);
        this.pool.setValidateAfterInactivity(2000);
        this.connectionOperator = Args.notNull(httpClientConnectionOperator, "HttpClientConnectionOperator");
        this.isShutDown = new AtomicBoolean(false);
        this.meterRegistry = meterRegistry;
    }

    @PostConstruct
    public void initialize() {
        Gauge.builder("http_connections", this.getPool(), CustomObservableCPool::getAvailable).tag("type", "available").description("Http available connections")
                .register(this.meterRegistry);
        Gauge.builder("http_connections", this.getPool(), CustomObservableCPool::getMaxTotal).tag("type", "total").description("Http total connections").register(this.meterRegistry);
        Gauge.builder("http_connections", this.getPool(), CustomObservableCPool::getLeased).tag("type", "leased").description("Http leased connections").register(this.meterRegistry);
        Gauge.builder("http_connections", this.getPool(), CustomObservableCPool::getPending).tag("type", "pending").description("Http pending connections")
                .register(this.meterRegistry);
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            shutdown();
        } finally {
            super.finalize();
        }
    }

    @Override
    public void close() {
        shutdown();
    }

    private String format(final HttpRoute route, final Object state) {
        final StringBuilder buf = new StringBuilder();
        buf.append("[route: ").append(route).append("]");
        if (state != null) {
            buf.append("[state: ").append(state).append("]");
        }
        return buf.toString();
    }

    private String formatStats(final HttpRoute route) {
        final StringBuilder buf = new StringBuilder();
        final PoolStats totals = this.pool.getTotalStats();
        final PoolStats stats = this.pool.getStats(route);
        buf.append("[total available: ").append(totals.getAvailable()).append("; ");
        buf.append("route allocated: ").append(stats.getLeased() + stats.getAvailable());
        buf.append(" of ").append(stats.getMax()).append("; ");
        buf.append("total allocated: ").append(totals.getLeased() + totals.getAvailable());
        buf.append(" of ").append(totals.getMax()).append("]");
        return buf.toString();
    }

    private String format(final CPoolEntry entry) {
        final StringBuilder buf = new StringBuilder();
        buf.append("[id: ").append(entry.getId()).append("]");
        buf.append("[route: ").append(entry.getRoute()).append("]");
        final Object state = entry.getState();
        if (state != null) {
            buf.append("[state: ").append(state).append("]");
        }
        return buf.toString();
    }

    private SocketConfig resolveSocketConfig(final HttpHost host) {
        SocketConfig socketConfig = this.configData.getSocketConfig(host);
        if (socketConfig == null) {
            socketConfig = this.configData.getDefaultSocketConfig();
        }
        if (socketConfig == null) {
            socketConfig = SocketConfig.DEFAULT;
        }
        return socketConfig;
    }

    @Override
    public ConnectionRequest requestConnection(
            final HttpRoute route,
            final Object state) {
        Args.notNull(route, "HTTP route");
        if (this.log.isDebugEnabled()) {
            this.log.debug("Connection request: " + format(route, state) + formatStats(route));
        }
        Asserts.check(!this.isShutDown.get(), "Connection pool shut down");
        final Future<CPoolEntry> future = this.pool.lease(route, state, null);
        return new ConnectionRequest() {

            @Override
            public boolean cancel() {
                return future.cancel(true);
            }

            @Override
            public HttpClientConnection get(
                    final long timeout,
                    final TimeUnit timeUnit) throws InterruptedException, ExecutionException, ConnectionPoolTimeoutException {
                final HttpClientConnection conn = leaseConnection(future, timeout, timeUnit);
                if (conn.isOpen()) {
                    final HttpHost host;
                    if (route.getProxyHost() != null) {
                        host = route.getProxyHost();
                    } else {
                        host = route.getTargetHost();
                    }
                    final SocketConfig socketConfig = resolveSocketConfig(host);
                    conn.setSocketTimeout(socketConfig.getSoTimeout());
                }
                return conn;
            }

        };

    }

    protected HttpClientConnection leaseConnection(
            final Future<CPoolEntry> future,
            final long timeout,
            final TimeUnit timeUnit) throws InterruptedException, ExecutionException, ConnectionPoolTimeoutException {
        final CPoolEntry entry;
        try {
            entry = future.get(timeout, timeUnit);
            if (entry == null || future.isCancelled()) {
                throw new ExecutionException(new CancellationException("Operation cancelled"));
            }
            Asserts.check(entry.getConnection() != null, "Pool entry with no connection");
            if (this.log.isDebugEnabled()) {
                this.log.debug("Connection leased: " + format(entry) + formatStats(entry.getRoute()));
            }
            return CPoolProxy.newProxy(entry);
        } catch (final TimeoutException ex) {
            throw new ConnectionPoolTimeoutException("Timeout waiting for connection from pool");
        }
    }

    @Override
    public void releaseConnection(
            final HttpClientConnection managedConn,
            final Object state,
            final long keepalive, final TimeUnit timeUnit) {
        Args.notNull(managedConn, "Managed connection");
        synchronized (managedConn) {
            final CPoolEntry entry = CPoolProxy.detach(managedConn);
            if (entry == null) {
                return;
            }
            final ManagedHttpClientConnection conn = entry.getConnection();
            try {
                if (conn.isOpen()) {
                    final TimeUnit effectiveUnit = timeUnit != null ? timeUnit : TimeUnit.MILLISECONDS;
                    entry.setState(state);
                    entry.updateExpiry(keepalive, effectiveUnit);
                    if (this.log.isDebugEnabled()) {
                        final String s;
                        if (keepalive > 0) {
                            s = "for " + (double) effectiveUnit.toMillis(keepalive) / 1000 + " seconds";
                        } else {
                            s = "indefinitely";
                        }
                        this.log.debug("Connection " + format(entry) + " can be kept alive " + s);
                    }
                    conn.setSocketTimeout(0);
                }
            } finally {
                this.pool.release(entry, conn.isOpen() && entry.isRouteComplete());
                if (this.log.isDebugEnabled()) {
                    this.log.debug("Connection released: " + format(entry) + formatStats(entry.getRoute()));
                }
            }
        }
    }

    @Override
    public void connect(
            final HttpClientConnection managedConn,
            final HttpRoute route,
            final int connectTimeout,
            final HttpContext context) throws IOException {
        Args.notNull(managedConn, "Managed Connection");
        Args.notNull(route, "HTTP route");
        final ManagedHttpClientConnection conn;
        synchronized (managedConn) {
            final CPoolEntry entry = CPoolProxy.getPoolEntry(managedConn);
            conn = entry.getConnection();
        }
        final HttpHost host;
        if (route.getProxyHost() != null) {
            host = route.getProxyHost();
        } else {
            host = route.getTargetHost();
        }
        this.connectionOperator.connect(
                conn, host, route.getLocalSocketAddress(), connectTimeout, resolveSocketConfig(host), context);
    }

    @Override
    public void upgrade(
            final HttpClientConnection managedConn,
            final HttpRoute route,
            final HttpContext context) throws IOException {
        Args.notNull(managedConn, "Managed Connection");
        Args.notNull(route, "HTTP route");
        final ManagedHttpClientConnection conn;
        synchronized (managedConn) {
            final CPoolEntry entry = CPoolProxy.getPoolEntry(managedConn);
            conn = entry.getConnection();
        }
        this.connectionOperator.upgrade(conn, route.getTargetHost(), context);
    }

    @Override
    public void routeComplete(
            final HttpClientConnection managedConn,
            final HttpRoute route,
            final HttpContext context) throws IOException {
        Args.notNull(managedConn, "Managed Connection");
        Args.notNull(route, "HTTP route");
        synchronized (managedConn) {
            final CPoolEntry entry = CPoolProxy.getPoolEntry(managedConn);
            entry.markRouteComplete();
        }
    }

    @Override
    public void shutdown() {
        if (this.isShutDown.compareAndSet(false, true)) {
            this.log.debug("Connection manager is shutting down");
            try {
                this.pool.enumLeased(new PoolEntryCallback<HttpRoute, ManagedHttpClientConnection>() {

                    @Override
                    public void process(final PoolEntry<HttpRoute, ManagedHttpClientConnection> entry) {
                        final ManagedHttpClientConnection connection = entry.getConnection();
                        if (connection != null) {
                            try {
                                connection.shutdown();
                            } catch (final IOException iox) {
                                if (log.isDebugEnabled()) {
                                    log.debug("I/O exception shutting down connection", iox);
                                }
                            }
                        }
                    }

                });
                this.pool.shutdown();
            } catch (final IOException ex) {
                this.log.debug("I/O exception shutting down connection manager", ex);
            }
            this.log.debug("Connection manager shut down");
        }
    }

    @Override
    public void closeIdleConnections(final long idleTimeout, final TimeUnit timeUnit) {
        if (this.log.isDebugEnabled()) {
            this.log.debug("Closing connections idle longer than " + idleTimeout + " " + timeUnit);
        }
        this.pool.closeIdle(idleTimeout, timeUnit);
    }

    @Override
    public void closeExpiredConnections() {
        this.log.debug("Closing expired connections");
        this.pool.closeExpired();
    }

    protected void enumAvailable(final PoolEntryCallback<HttpRoute, ManagedHttpClientConnection> callback) {
        this.pool.enumAvailable(callback);
    }


    protected void enumLeased(final PoolEntryCallback<HttpRoute, ManagedHttpClientConnection> callback) {
        this.pool.enumLeased(callback);
    }

    @Override
    public int getMaxTotal() {
        return this.pool.getMaxTotal();
    }

    @Override
    public void setMaxTotal(final int max) {
        this.pool.setMaxTotal(max);
    }

    @Override
    public int getDefaultMaxPerRoute() {
        return this.pool.getDefaultMaxPerRoute();
    }

    @Override
    public void setDefaultMaxPerRoute(final int max) {
        this.pool.setDefaultMaxPerRoute(max);
    }

    @Override
    public int getMaxPerRoute(final HttpRoute route) {
        return this.pool.getMaxPerRoute(route);
    }

    @Override
    public void setMaxPerRoute(final HttpRoute route, final int max) {
        this.pool.setMaxPerRoute(route, max);
    }

    @Override
    public PoolStats getTotalStats() {
        return this.pool.getTotalStats();
    }

    @Override
    public PoolStats getStats(final HttpRoute route) {
        return this.pool.getStats(route);
    }

    /**
     * @since 4.4
     */
    public Set<HttpRoute> getRoutes() {
        return this.pool.getRoutes();
    }

    public SocketConfig getDefaultSocketConfig() {
        return this.configData.getDefaultSocketConfig();
    }


    public void setDefaultSocketConfig(final SocketConfig defaultSocketConfig) {
        this.configData.setDefaultSocketConfig(defaultSocketConfig);
    }


    public ConnectionConfig getDefaultConnectionConfig() {
        return this.configData.getDefaultConnectionConfig();
    }

    public CustomObservableCPool getPool() {
        return this.pool;
    }

    public void setDefaultConnectionConfig(final ConnectionConfig defaultConnectionConfig) {
        this.configData.setDefaultConnectionConfig(defaultConnectionConfig);
    }


    public SocketConfig getSocketConfig(final HttpHost host) {
        return this.configData.getSocketConfig(host);
    }


    public void setSocketConfig(final HttpHost host, final SocketConfig socketConfig) {
        this.configData.setSocketConfig(host, socketConfig);
    }


    public ConnectionConfig getConnectionConfig(final HttpHost host) {
        return this.configData.getConnectionConfig(host);
    }


    public void setConnectionConfig(final HttpHost host, final ConnectionConfig connectionConfig) {
        this.configData.setConnectionConfig(host, connectionConfig);
    }

    /**
     * @see #setValidateAfterInactivity(int)
     * @since 4.4
     */
    public int getValidateAfterInactivity() {
        return pool.getValidateAfterInactivity();
    }

    /**
     * Defines period of inactivity in milliseconds after which persistent connections must
     * be re-validated prior to being {@link #leaseConnection(java.util.concurrent.Future,
     * long, java.util.concurrent.TimeUnit) leased} to the consumer. Non-positive value passed
     * to this method disables connection validation. This check helps detect connections
     * that have become stale (half-closed) while kept inactive in the pool.
     *
     * @see #leaseConnection(java.util.concurrent.Future, long, java.util.concurrent.TimeUnit)
     * @since 4.4
     */
    public void setValidateAfterInactivity(final int ms) {
        pool.setValidateAfterInactivity(ms);
    }

    static class ConfigData {

        private final Map<HttpHost, SocketConfig> socketConfigMap;
        private final Map<HttpHost, ConnectionConfig> connectionConfigMap;
        private volatile SocketConfig defaultSocketConfig;
        private volatile ConnectionConfig defaultConnectionConfig;

        ConfigData() {
            super();
            this.socketConfigMap = new ConcurrentHashMap<HttpHost, SocketConfig>();
            this.connectionConfigMap = new ConcurrentHashMap<HttpHost, ConnectionConfig>();
        }

        public SocketConfig getDefaultSocketConfig() {
            return this.defaultSocketConfig;
        }

        public void setDefaultSocketConfig(final SocketConfig defaultSocketConfig) {
            this.defaultSocketConfig = defaultSocketConfig;
        }

        public ConnectionConfig getDefaultConnectionConfig() {
            return this.defaultConnectionConfig;
        }

        public void setDefaultConnectionConfig(final ConnectionConfig defaultConnectionConfig) {
            this.defaultConnectionConfig = defaultConnectionConfig;
        }

        public SocketConfig getSocketConfig(final HttpHost host) {
            return this.socketConfigMap.get(host);
        }

        public void setSocketConfig(final HttpHost host, final SocketConfig socketConfig) {
            this.socketConfigMap.put(host, socketConfig);
        }

        public ConnectionConfig getConnectionConfig(final HttpHost host) {
            return this.connectionConfigMap.get(host);
        }

        public void setConnectionConfig(final HttpHost host, final ConnectionConfig connectionConfig) {
            this.connectionConfigMap.put(host, connectionConfig);
        }

    }

    static class InternalConnectionFactory implements ConnFactory<HttpRoute, ManagedHttpClientConnection> {

        private final CustomPoolingHttpClientConnectionManager.ConfigData configData;
        private final HttpConnectionFactory<HttpRoute, ManagedHttpClientConnection> connFactory;

        InternalConnectionFactory(
                final CustomPoolingHttpClientConnectionManager.ConfigData configData,
                final HttpConnectionFactory<HttpRoute, ManagedHttpClientConnection> connFactory) {
            super();
            this.configData = configData != null ? configData : new CustomPoolingHttpClientConnectionManager.ConfigData();
            this.connFactory = connFactory != null ? connFactory :
                    ManagedHttpClientConnectionFactory.INSTANCE;
        }

        @Override
        public ManagedHttpClientConnection create(final HttpRoute route) throws IOException {
            ConnectionConfig config = null;
            if (route.getProxyHost() != null) {
                config = this.configData.getConnectionConfig(route.getProxyHost());
            }
            if (config == null) {
                config = this.configData.getConnectionConfig(route.getTargetHost());
            }
            if (config == null) {
                config = this.configData.getDefaultConnectionConfig();
            }
            if (config == null) {
                config = ConnectionConfig.DEFAULT;
            }
            return this.connFactory.create(route, config);
        }
    }
}
