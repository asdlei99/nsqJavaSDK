package com.youzan.nsq.client;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.commons.pool2.impl.GenericKeyedObjectPool;
import org.apache.commons.pool2.impl.GenericKeyedObjectPoolConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.youzan.nsq.client.core.Client;
import com.youzan.nsq.client.core.KeyedConnectionPoolFactory;
import com.youzan.nsq.client.core.NSQConnection;
import com.youzan.nsq.client.core.NSQSimpleClient;
import com.youzan.nsq.client.core.command.Close;
import com.youzan.nsq.client.core.command.Finish;
import com.youzan.nsq.client.core.command.NSQCommand;
import com.youzan.nsq.client.core.command.Rdy;
import com.youzan.nsq.client.core.command.ReQueue;
import com.youzan.nsq.client.core.command.Sub;
import com.youzan.nsq.client.entity.Address;
import com.youzan.nsq.client.entity.NSQConfig;
import com.youzan.nsq.client.entity.NSQMessage;
import com.youzan.nsq.client.entity.Response;
import com.youzan.nsq.client.exception.NSQException;
import com.youzan.nsq.client.network.frame.ErrorFrame;
import com.youzan.nsq.client.network.frame.MessageFrame;
import com.youzan.nsq.client.network.frame.NSQFrame;
import com.youzan.nsq.client.network.frame.NSQFrame.FrameType;
import com.youzan.util.ConcurrentSortedSet;
import com.youzan.util.IOUtil;
import com.youzan.util.NamedThreadFactory;

/**
 * Use {@code NSQConfig} to set the lookup cluster. <br />
 * Expose to Client Code. Connect to one cluster(includes many brokers).
 * 
 * 
 * @author zhaoxi (linzuxiong)
 * @email linzuxiong1988@gmail.com
 *
 */
public class ConsumerImplV2 implements Consumer {

    private static final Logger logger = LoggerFactory.getLogger(ConsumerImplV2.class);
    private volatile boolean started = false;
    private final Client simpleClient;
    private final NSQConfig config;
    private final GenericKeyedObjectPoolConfig poolConfig;
    private final KeyedConnectionPoolFactory factory;
    private GenericKeyedObjectPool<Address, NSQConnection> bigPool = null;
    private final AtomicInteger success = new AtomicInteger(0);
    private final AtomicInteger total = new AtomicInteger(0);
    /**
     * Record the client's request time
     */
    private long lastTimeInMillisOfClientRequest = System.currentTimeMillis();

    /*-
     * =========================================================================
     *                          
     * =========================================================================
     */
    private final ConcurrentHashMap<Address, Set<NSQConnection>> holdingConnections = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors
            .newSingleThreadScheduledExecutor(new NamedThreadFactory(this.getClass().getName(), Thread.NORM_PRIORITY));

    /*-
     * =========================================================================
     *                          Client delegate to me
     * =========================================================================
     */
    private final MessageHandler handler;
    private final ExecutorService executor = Executors.newFixedThreadPool(
            Runtime.getRuntime().availableProcessors() * 4,
            new NamedThreadFactory(this.getClass().getName() + "-ClientBusiness", Thread.MAX_PRIORITY));
    private volatile Optional<ScheduledFuture<?>> timeout = Optional.empty();
    private volatile long nextTimeout = 0;
    private final int messagesPerBatch = 10;
    private final AtomicBoolean closing = new AtomicBoolean(false);

    /**
     * @param config
     * @param handler
     */
    public ConsumerImplV2(NSQConfig config, MessageHandler handler) {
        this.config = config;
        this.handler = handler;

        this.poolConfig = new GenericKeyedObjectPoolConfig();
        this.simpleClient = new NSQSimpleClient(config.getLookupAddresses(), config.getTopic());
        this.factory = new KeyedConnectionPoolFactory(this.config, this);
    }

    @Override
    public void start() throws NSQException {
        final String topic = this.config.getTopic();
        if (topic == null || topic.isEmpty()) {
            throw new NSQException("Please set topic name using {@code NSQConfig}");
        }
        if (this.config.getConsumerName() == null || this.config.getConsumerName().isEmpty()) {
            throw new IllegalArgumentException("Consumer Name is blank! Please check it!");
        }
        if (!this.started) {
            this.started = true;
            // setting all of the configs
            this.poolConfig.setLifo(false);
            this.poolConfig.setFairness(true);
            this.poolConfig.setTestOnBorrow(false);
            this.poolConfig.setJmxEnabled(false);
            this.poolConfig.setMinEvictableIdleTimeMillis(24 * 3600 * 1000L);
            this.poolConfig.setMinIdlePerKey(this.config.getThreadPoolSize4IO());
            this.poolConfig.setMaxIdlePerKey(this.config.getThreadPoolSize4IO());
            this.poolConfig.setMaxTotalPerKey(this.config.getThreadPoolSize4IO());
            // aquire connection waiting time
            this.poolConfig.setMaxWaitMillis(500);
            this.poolConfig.setBlockWhenExhausted(true);
            this.poolConfig.setTestWhileIdle(true);
            try {
                this.simpleClient.start();
            } catch (Exception e) {
                logger.error("Exception", e);
            }
            createBigPool();
            // POST
            connect();
            keepConnecting();
        }
    }

    /**
     * 
     */
    private void createBigPool() {
        this.bigPool = new GenericKeyedObjectPool<>(this.factory, this.poolConfig);
    }

    /**
     * schedule action
     */
    private void keepConnecting() {
        final int delay = _r.nextInt(120) + 60; // seconds
        scheduler.scheduleWithFixedDelay(() -> {
            try {
                connect();
            } catch (Exception e) {
                logger.error("Exception", e);
            }
        }, delay, 1 * 60, TimeUnit.SECONDS);
    }

    private void connect() {
        final Set<Address> broken = new HashSet<>();
        holdingConnections.values().parallelStream().forEach((conns) -> {
            for (final NSQConnection c : conns) {
                try {
                    if (!c.isConnected()) {
                        c.close();
                        broken.add(c.getAddress());
                    }
                } catch (Exception e) {
                    logger.error("Exception occurs while detecting broken connections!", e);
                }
            }
        });

        final Set<Address> newDataNodes = getDataNodes().newSet();
        final Set<Address> oldDataNodes = new HashSet<>(this.holdingConnections.keySet());
        logger.debug("Prepare to connect new NSQd: {}, old NSQd: {} .", newDataNodes, oldDataNodes);
        if (newDataNodes.isEmpty() && oldDataNodes.isEmpty()) {
            return;
        }
        if (newDataNodes.isEmpty()) {
            logger.error("It can not get new DataNodes (NSQd). It will create a new pool next time!");
        }
        /*-
         * =====================================================================
         *                                Step 1:
         *                    以newDataNodes为主的差集: 新建Brokers
         * =====================================================================
         */
        final Set<Address> except1 = new HashSet<>(newDataNodes);
        except1.removeAll(oldDataNodes);
        if (!except1.isEmpty()) {
            newConnections(except1);
        } else {
            logger.debug("No need to create new NSQd connections!");
        }
        /*-
         * =====================================================================
         *                                Step 2:
         *                    以oldDataNodes为主的差集: 删除Brokers
         * =====================================================================
         */
        final Set<Address> except2 = new HashSet<>(oldDataNodes);
        except2.removeAll(newDataNodes);
        if (except2.isEmpty()) {
            logger.debug("No need to destory old NSQd connections!");
        } else {
            except2.parallelStream().forEach((address) -> {
                if (address == null) {
                    return;
                }
                bigPool.clear(address);
                if (holdingConnections.containsKey(address)) {
                    final Set<NSQConnection> conns = holdingConnections.get(address);
                    if (conns != null) {
                        conns.forEach((c) -> {
                            try {
                                backoff(c);
                            } catch (Exception e) {
                                logger.error("It can not backoff the connection!", e);
                            } finally {
                                IOUtil.closeQuietly(c);
                            }
                        });
                    }
                }
                holdingConnections.remove(address);
            });
        }
        /*-
         * =====================================================================
         *                                Step 3:
         *                          干掉Broken Brokers.
         * =====================================================================
         */
        broken.parallelStream().forEach((address) -> {
            if (address == null) {
                return;
            }
            try {
                holdingConnections.remove(address);
                factory.clear(address);
                bigPool.clear(address);
            } catch (Exception e) {
                logger.error("Exception", e);
            }
        });
        /*-
         * =====================================================================
         *                                Step 4:
         *                          Clean up local resources
         * =====================================================================
         */
        broken.clear();
        except1.clear();
        except2.clear();
    }

    /**
     * @param brokers
     */
    private void newConnections(final Set<Address> brokers) {
        brokers.parallelStream().forEach((address) -> {
            try {
                newConnections4OneBroker(address);
            } catch (Exception e) {
                logger.error("Exception", e);
            }
        });
    }

    /**
     * @param address
     */
    private void newConnections4OneBroker(Address address) {
        if (address == null) {
            logger.error("Your input address is blank!");
            return;
        }
        try {
            bigPool.clear(address);
            bigPool.preparePool(address);
        } catch (Exception e) {
            logger.error("Exception", e);
        }
        // create new pool(connect to one broker)
        final List<NSQConnection> okConns = new ArrayList<>(config.getThreadPoolSize4IO());
        for (int i = 0; i < config.getThreadPoolSize4IO(); i++) {
            NSQConnection newConn = null;
            try {
                newConn = bigPool.borrowObject(address);
                initConn(newConn); // subscribe
                if (!holdingConnections.containsKey(address)) {
                    holdingConnections.putIfAbsent(address, new HashSet<>());
                }
                final Set<NSQConnection> conns = holdingConnections.get(address);
                conns.add(newConn);

                okConns.add(newConn);
            } catch (Exception e) {
                logger.error("Exception: {}", address, e);
                if (newConn != null) {
                    bigPool.returnObject(address, newConn);
                }
            }
        }
        // finally
        for (NSQConnection c : okConns) {
            try {
                bigPool.returnObject(c.getAddress(), c);
            } catch (Exception e) {
                logger.error("Exception", e);
            }
        }
        if (okConns.size() == config.getThreadPoolSize4IO()) {
            logger.info("Having created a pool for one broker, it felt good.");
        } else {
            logger.info("Want the pool size {}, actually the size {}", config.getThreadPoolSize4IO(), okConns.size());
        }
        okConns.clear();
    }

    /**
     * @param newConn
     * @throws TimeoutException
     * @throws NSQException
     */
    private void initConn(NSQConnection newConn) throws TimeoutException, NSQException {
        final NSQFrame frame = newConn.commandAndGetResponse(new Sub(config.getTopic(), config.getConsumerName()));
        if (frame != null && frame.getType() == FrameType.ERROR_FRAME) {
            final ErrorFrame err = (ErrorFrame) frame;
            if (err != null) {
                final Address address = newConn.getAddress();
                switch (err.getError()) {
                    case E_FAILED_ON_NOT_LEADER: {
                    }
                    case E_FAILED_ON_NOT_WRITABLE: {
                    }
                    case E_TOPIC_NOT_EXIST: {
                        if (address != null) {
                            holdingConnections.remove(address);
                            factory.clear(address);
                            bigPool.clear(address);
                        }
                    }
                    default: {
                        throw new NSQException("Unkown response error!");
                    }
                }
            }
        }
        newConn.command(new Rdy(messagesPerBatch));
        logger.info("Rdy {} message! It is new connection!", messagesPerBatch);
    }

    /**
     * @param millisecond
     */
    private void sleep(final int millisecond) {
        try {
            Thread.sleep(millisecond);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.error("System is too busy! Please check it!", e);
        }
    }

    @Override
    public void incoming(final NSQFrame frame, final NSQConnection conn) {
        if (frame instanceof MessageFrame) {
            final MessageFrame msg = (MessageFrame) frame;
            final NSQMessage message = new NSQMessage(msg.getTimestamp(), msg.getAttempts(), msg.getMessageID(),
                    msg.getMessageBody());
            processMessage(message, conn);
            return;
        }
        simpleClient.incoming(frame, conn);
    }

    protected void processMessage(final NSQMessage message, final NSQConnection conn) {
        if (handler == null) {
            logger.error("No MessageHandler then drop the message {}", message);
            return;
        }
        try {
            executor.execute(() -> {
                try {
                    consume(message, conn);
                } catch (Exception e) {
                    logger.error("Exception", e);
                }
            });
            if (nextTimeout > 0) {
                updateTimeout(conn, -500);
            }
        } catch (RejectedExecutionException re) {
            updateTimeout(conn, 500);
        }
        final long nowTotal = total.incrementAndGet();
        if (nowTotal % messagesPerBatch > (messagesPerBatch / 2) && closing.get() == false) {
            conn.command(new Rdy(messagesPerBatch));
        }
    }

    /**
     * @param message
     * @param conn
     */
    private void consume(final NSQMessage message, final NSQConnection conn) {
        logger.debug("Having consumed the message {}, client showed great anxiety!", message.toString());
        boolean ok = false;
        int c = 0;
        while (c++ < 2) { // 0, 1
            try {
                handler.process(message);
                ok = true;
                break;
            } catch (Exception e) {
                ok = false;
                logger.error("Current Retries: {}, Exception occurs...", c, e);
            }
        }
        // The client commands requeue into NSQd.
        final Integer timeout = message.getNextConsumingInSecond();
        final NSQCommand cmd;
        if (timeout != null) {
            cmd = new ReQueue(message.getMessageID(), message.getNextConsumingInSecond());
            if (message.getReadableAttempts() > 10) {
                logger.error("Processing 10 times is still a failure! {}", message);
            }
        } else {
            cmd = new Finish(message.getMessageID());
            if (!ok) {
                logger.error("{}, exception occurs but you don't catch it!", message);
            }
        }
        conn.command(cmd);
    }

    /**
     * @param conn
     * @param change
     */
    private void updateTimeout(final NSQConnection conn, final int change) {
        backoff(conn);
        logger.debug("RDY 0! Halt Flow.");
        if (timeout.isPresent()) {
            timeout.get().cancel(true);
        }
        if (closing.get()) {
            return;
        }
        final Date newTimeout = calculateTimeoutDate(change);
        if (newTimeout != null) {
            timeout = Optional.of(scheduler.schedule(() -> {
                conn.command(new Rdy(1)); // test the waters
            }, 0, TimeUnit.MILLISECONDS));
        }
    }

    /**
     * @param change
     * @return
     */
    private Date calculateTimeoutDate(final int change) {
        if (System.currentTimeMillis() - nextTimeout + change > 50) {
            nextTimeout += change;
            return new Date();
        } else {
            nextTimeout = 0;
            return null;
        }
    }

    @Override
    public void backoff(NSQConnection conn) {
        simpleClient.backoff(conn);
    }

    @Override
    public void close() {
        closing.set(true);
        started = false;

        cleanClose();
        if (factory != null) {
            factory.close();
        }
        if (bigPool != null) {
            bigPool.close();
        }

    }

    private void cleanClose() {
        holdingConnections.values().parallelStream().forEach((conns) -> {
            for (final NSQConnection c : conns) {
                try {
                    backoff(c);
                    final NSQFrame frame = c.commandAndGetResponse(Close.getInstance());
                    if (frame != null && frame.getType() == FrameType.ERROR_FRAME) {
                        final Response err = ((ErrorFrame) frame).getError();
                        if (err != null) {
                            logger.error(err.getContent());
                        }
                    }
                } catch (Exception e) {
                    logger.error("Exception", e);
                } finally {
                    IOUtil.closeQuietly(c);
                }
            }
        });
        holdingConnections.clear();
    }

    @Override
    public ConcurrentSortedSet<Address> getDataNodes() {
        return simpleClient.getDataNodes();
    }
}
