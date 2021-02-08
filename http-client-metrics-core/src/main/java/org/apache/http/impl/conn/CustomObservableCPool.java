package org.apache.http.impl.conn;

import io.micrometer.core.instrument.MeterRegistry;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.http.conn.ManagedHttpClientConnection;
import org.apache.http.conn.routing.HttpRoute;
import org.apache.http.pool.ConnFactory;
import org.apache.http.pool.CustomAbstractConnPool;
import org.apache.http.pool.PoolEntryCallback;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * User: rajeshgupta
 * Date: 30/12/20
 */
public class CustomObservableCPool extends CustomAbstractConnPool<HttpRoute, ManagedHttpClientConnection, CPoolEntry> {

    private static final AtomicLong COUNTER = new AtomicLong();

    private final Log log = LogFactory.getLog(CPool.class);
    private final long timeToLive;
    private final TimeUnit timeUnit;

    public CustomObservableCPool(final ConnFactory<HttpRoute, ManagedHttpClientConnection> connFactory,
                                 final MeterRegistry meterRegistry, final int defaultMaxPerRoute, final int maxTotal, final long timeToLive, final TimeUnit timeUnit) {
        super(connFactory, meterRegistry, defaultMaxPerRoute, maxTotal);
        this.timeToLive = timeToLive;
        this.timeUnit = timeUnit;
    }

    /**
     * Creates a new entry for the given connection with the given route.
     *
     * @param route
     * @param conn
     */
    @Override
    protected CPoolEntry createEntry(final HttpRoute route, final ManagedHttpClientConnection conn) {
        final String id = Long.toString(COUNTER.getAndIncrement());
        return new CPoolEntry(this.log, id, route, conn, this.timeToLive, this.timeUnit);
    }

    @Override
    protected void enumAvailable(final PoolEntryCallback<HttpRoute, ManagedHttpClientConnection> callback) {
        super.enumAvailable(callback);
    }

    @Override
    protected void enumLeased(final PoolEntryCallback<HttpRoute, ManagedHttpClientConnection> callback) {
        super.enumLeased(callback);
    }
}
