package com.youzan.nsq.client.core;

import com.youzan.nsq.client.IConsumeInfo;
import com.youzan.nsq.client.core.command.Rdy;
import com.youzan.nsq.client.entity.Address;
import com.youzan.util.NamedThreadFactory;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Created by lin on 17/6/26.
 */
public class ConnectionManager {
    private final static Logger logger = LoggerFactory.getLogger(ConnectionManager.class.getName());
    private Map<String, ConnectionWrapperSet> topic2Subs = new ConcurrentHashMap<>();

    //success attemp of total rdy proofread count
    private final AtomicInteger proofreadCnt = new AtomicInteger(0);
    private final long PROOFREAD_INTERVAL = 30 * 60 * 1000L;
    private static final float PROOFREAD_FACTOR_DELTA = 0.1f;
    private static final float PROOFREAD_FACTOR_FLOOR = 0.1f;
    private static final float PROOFREAD_FACTOR_DEFAULT = 1f;
    private float proofreadFactor = PROOFREAD_FACTOR_DEFAULT;

    //executor for backoff & resume
    private final ExecutorService exec = Executors.newCachedThreadPool(new NamedThreadFactory("connMgr-job", Thread.NORM_PRIORITY));
    //schedule executor for backoff resume
    private final ScheduledExecutorService delayExec = Executors.newSingleThreadScheduledExecutor();

    private final float THRESDHOLD = 1.5f;
    private final float WATER_HIGH = 1.75f;
    private final int INIT_DELAY = 20;
    private final int INTERVAL = 5;
    private final int INIT_RDY = 1;
    private final int RDY_TIMEOUT = 100;

    private AtomicBoolean start = new AtomicBoolean(false);
    private final Runnable REDISTRIBUTE_RUNNABLE = new Runnable() {
        @Override
        public void run() {
            redistributeRdy(ci.getLoadFactor(), ci.isConsumptionEstimateElapseTimeout(), ci.getRdyPerConnection());
        }
    };

    private final IConsumeInfo ci;
    public ConnectionManager(IConsumeInfo consumer) {
        this.ci = consumer;
    }

    /**
     *NSQConnectionWrapper, need it to override hashcode
     */
    public static class NSQConnectionWrapper extends Address {
        final private NSQConnection conn;

        public NSQConnectionWrapper(final NSQConnection conn) {
            super(conn.getAddress());
            this.conn = conn;
        }

        public NSQConnection getConn() {
            return conn;
        }
    }

    public static class ConnectionWrapperSet extends HashSet<NSQConnectionWrapper> {
        private final AtomicInteger totalRdy = new AtomicInteger(0);
        private final AtomicBoolean isBackOff = new AtomicBoolean(false);
        private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
        private volatile float proofreadFactor = 1.0f;
        private volatile long lastProofread = System.currentTimeMillis();

        public long getLastProofread() {
            return this.lastProofread;
        }

        public void setLastProofread(long proofreadTimeStamp) {
            this.lastProofread = proofreadTimeStamp;
        }

        public void addTotalRdy(int rdy) {
            int rdyTotal = this.totalRdy.addAndGet(rdy);
            assert rdyTotal >= 0;
            float proofreadFactor = this.proofreadFactor;
            if(proofreadFactor - PROOFREAD_FACTOR_DELTA >= PROOFREAD_FACTOR_FLOOR)
                this.proofreadFactor = proofreadFactor - PROOFREAD_FACTOR_DELTA;
            else {
                this.proofreadFactor = PROOFREAD_FACTOR_FLOOR;
            }
        }

        public int setTotalRdy(int newTotalrdy) {
            assert newTotalrdy >= 0;
            int oldRdy = this.totalRdy.get();
            if(oldRdy != newTotalrdy && this.totalRdy.compareAndSet(oldRdy, newTotalrdy)) {
                this.setLastProofread(System.currentTimeMillis());
                //restore factor to defaut
                this.proofreadFactor = PROOFREAD_FACTOR_DEFAULT;
                return oldRdy;
            }
            return -1;
        }

        public float getProofreadFactor() {
            return this.proofreadFactor;
        }

         public int getTotalRdy() {
             return this.totalRdy.get();
         }

         public boolean backoff() {
            return isBackOff.compareAndSet(Boolean.FALSE, Boolean.TRUE);
         }

         public boolean resume() {
            return isBackOff.compareAndSet(Boolean.TRUE, Boolean.FALSE);
         }

         public boolean isBackoff() {
            return isBackOff.get();
         }

         public boolean remove(final Collection<NSQConnectionWrapper> addrs) {
            boolean modified = false;
            //update rdy after remove one connection wrapper
             for(Iterator<NSQConnectionWrapper> ite = addrs.iterator(); ite.hasNext(); ) {
                 NSQConnectionWrapper wrapper = ite.next();
                if(this.remove(wrapper)) {
                    addTotalRdy(-1 * wrapper.getConn().getCurrentRdyCount());
                    modified |= Boolean.TRUE;
                }
             }
            return modified;
         }

         public void writeLock() {
            this.lock.writeLock().lock();
         }

         public void writeUnlock() {
             this.lock.writeLock().unlock();
         }
    }


    private void proofreadTotalRdy() {
        for(String topic : this.topic2Subs.keySet()) {
            proofreadTotalRdy(topic);
        }
        logger.info("rdy proofready end. Total {} topics' connections affected after this batch.");
    }

    /**
     * proofread total rdy of current connection manager
     */
    private void proofreadTotalRdy(String topic) {
        AtomicInteger totalRdy = new AtomicInteger(0);
        final ConnectionWrapperSet cws = this.topic2Subs.get(topic);
        if (null != cws) {
            cws.writeLock();
            try{
               logger.info("Collecting Rdy for connections, topic {}...", topic);
               for(Iterator<NSQConnectionWrapper> ite = cws.iterator(); ite.hasNext(); ) {
                   NSQConnectionWrapper conWrapper = ite.next();
                   totalRdy.addAndGet(conWrapper.getConn().getCurrentRdyCount());
               }
               if(totalRdy.get() > 0) {
                  int oldTotal = cws.setTotalRdy(totalRdy.get());
                  logger.info("Update total rdy for connections, topic {}, from {} to {}", topic, oldTotal, totalRdy.get());
                  if(oldTotal > 0)
                      proofreadCnt.incrementAndGet();
               }
            } finally {
                cws.writeUnlock();
            }
        }
    }

    public void start() {
        if(!start.compareAndSet(false, true))
            return;
        delayExec.scheduleWithFixedDelay(REDISTRIBUTE_RUNNABLE, INIT_DELAY, INTERVAL, TimeUnit.SECONDS);
    }

    public void start(int initDelay) {
        if(!start.compareAndSet(false, true))
            return;
        delayExec.scheduleWithFixedDelay(REDISTRIBUTE_RUNNABLE, initDelay, INTERVAL, TimeUnit.SECONDS);
    }

    boolean isStart() {
        return start.get();
    }

    //TODO: close
    public void close() {
        delayExec.shutdownNow();
    }

    /**
     * Not-thread safe, this method is invoked in a {@link com.youzan.nsq.client.Consumer}
     * @param topic     topic for subscribe
     * @param subscriber subscriber
     * @param rdy RDY to initialize connection
     */
    public void subscribe(String topic, final NSQConnection subscriber, int rdy) {
        if (null == subscriber || null == topic || topic.isEmpty() || !topic.equals(subscriber.getTopic().getTopicText())) {
            throw new IllegalArgumentException("topic: " + topic + " connection: " + subscriber);
        }

        if(!topic2Subs.containsKey(topic)) {
            topic2Subs.put(topic, new ConnectionWrapperSet());
        }

        final ConnectionWrapperSet subs = topic2Subs.get(topic);
        subs.writeLock();
        try{
            subs.add(new NSQConnectionWrapper(subscriber));
            if(!subs.isBackoff()) {
                final CountDownLatch latch = new CountDownLatch(1);
                subscriber.onRdy(rdy, new IRdyCallback() {
                    @Override
                    public void onUpdated(int newRdy, int lastRdy) {
                        subs.addTotalRdy(subscriber.getCurrentRdyCount());
                        latch.countDown();
                    }
                });
                try {
                    latch.await(RDY_TIMEOUT, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    logger.error("Interrupted waiting for rdy update for subscribe, topic {}, Connection {}", topic, subscriber);
                }
            } else {
                backoff(subscriber);
            }
        } finally {
            subs.writeUnlock();
        }
    }

    public void subscribe(String topic, final NSQConnection subscriber) {
       subscribe(topic, subscriber, INIT_RDY);
    }

    /**
     * Not-thread safe, remove connection according to pass in addresses belong to topic
     * @param topic2ConWrappers topic to {@link NSQConnectionWrapper} collection map
     */
    public boolean remove(final Map<String, List<NSQConnectionWrapper>> topic2ConWrappers) {
        boolean removed = false;
        for(String topic : topic2ConWrappers.keySet()) {
            if (topic2Subs.containsKey(topic)) {
                ConnectionWrapperSet subs = topic2Subs.get(topic);
                subs.writeLock();
                try {
                    removed = removed | subs.remove(topic2ConWrappers.get(topic));
                    if(subs.size() == 0) {
                        topic2Subs.remove(topic);
                    }
                } finally {
                    subs.writeUnlock();
                }
            }
        }
        return removed;
    }

    /**
     * backoff a single connection to nsqd, regardless of whether topic is backed off.
     * @param conn nsqd connection to backoff, connection manager check if connection belongs to current manager,
     *             backoff when it does.
     */
    public void backoff(final NSQConnection conn) {
        String topic = conn.getTopic().getTopicText();
        if (!topic2Subs.containsKey(topic)) {
            logger.info("Subscriber for topic {} does not exist.");
            return;
        }

        NSQConnectionWrapper connWrapper = new NSQConnectionWrapper(conn);
        final ConnectionWrapperSet conWrapperSet = topic2Subs.get(topic);
        if(conWrapperSet.contains(connWrapper)) {
            final CountDownLatch latch = new CountDownLatch(1);
            conn.onBackoff(new IRdyCallback() {
                @Override
                public void onUpdated(int newRdy, int lastRdy) {
                    conWrapperSet.addTotalRdy(newRdy - lastRdy);
                    latch.countDown();
                }
            });
            try {
                latch.await(RDY_TIMEOUT, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                logger.error("Interrupted waiting for rdy update for backoff, connection {}", conn);
            }
        } else {
            logger.error("Connection {} does not belong to current consumer.", conn);
        }
    }

    /**
     * backoff connections to a topic.
     * @param topic topic to backoff.
     */
    public void backoff(final String topic, final CountDownLatch latch) {
        if (!topic2Subs.containsKey(topic)) {
            logger.info("Subscriber for topic {} does not exist.");
            return;
        }

        final ConnectionWrapperSet subs = topic2Subs.get(topic);
        if(null != subs) {
            subs.writeLock();
            try {
                if (!subs.backoff()) {
                    logger.info("topic {} already backoff.", topic);
                    if(null != latch)
                        latch.countDown();
                    return;
                }
                final int latchCount = subs.size();
                final CountDownLatch backoffLatch = new CountDownLatch(latchCount);
                exec.submit(new Runnable() {
                    public void run() {
                        for (NSQConnectionWrapper sub : subs) {
                            sub.getConn().onBackoff(new IRdyCallback() {
                                @Override
                                public void onUpdated(int newRdy, int lastRdy) {
                                    subs.addTotalRdy(newRdy - lastRdy);
                                    backoffLatch.countDown();
                                }
                            });
                        }
                    }
                });

                try{
                    if (!backoffLatch.await(latchCount * RDY_TIMEOUT, TimeUnit.MILLISECONDS)) {
                        logger.error("Timeout backoff topic connections {}", topic);
                    } else if (null != latch) {
                        latch.countDown();
                        logger.info("Backoff connections for topic {}", topic);
                    }
                } catch (InterruptedException e) {
                    logger.error("Interrupted while waiting for back off on all connections for {}", topic);
                }
            } finally {
                subs.writeUnlock();
            }
        }
    }


    public void resume(final String topic, final CountDownLatch latch) {
        if (!topic2Subs.containsKey(topic)) {
            logger.info("Subscriber for topic {} does not exist.");
            return;
        }

        final ConnectionWrapperSet subs = topic2Subs.get(topic);
        if(null != subs) {
            subs.writeLock();
            try {
                if(!subs.resume()) {
                    logger.info("topic {} is already in resumed.", topic);
                    if(null != latch)
                        latch.countDown();
                    return;
                }
                int latchCount = subs.size();
                final CountDownLatch resumeLatch = new CountDownLatch(latchCount);
                exec.submit(new Runnable() {
                    @Override
                    public void run() {
                        for (NSQConnectionWrapper sub : subs) {
                            sub.getConn().onResume(new IRdyCallback() {
                                @Override
                                public void onUpdated(int newRdy, int lastRdy) {
                                    subs.addTotalRdy(newRdy - lastRdy);
                                    resumeLatch.countDown();
                                }
                            });
                        }
                    }
                });

                try {
                    if (!resumeLatch.await(latchCount * RDY_TIMEOUT, TimeUnit.MILLISECONDS)) {
                        logger.error("Timeout for resume topic connections {}", topic);
                    } else if (null != latch) {
                        latch.countDown();
                        logger.info("Resume connections for topic {}", topic);
                    }
                } catch (InterruptedException e) {
                    logger.error("Interrupted while waiting for resume on all connections for {}", topic);
                } finally {
                }
            } finally {
                subs.writeUnlock();
            }
        }
    }

    private void mayIncreaseRdy(final NSQConnection con, int rdyPerCon, final ConnectionWrapperSet conSet, final CountDownLatch latch) {
        int currentRdy = con.getCurrentRdyCount();
        final int availableRdy = rdyPerCon * conSet.size() - conSet.getTotalRdy() + currentRdy;
        final int expectedRdy = con.getExpectedRdy();
        if (availableRdy > 0) {
            int ceilingRdy = availableRdy > expectedRdy ? expectedRdy : availableRdy;
//                                  TODO now we do not exceed expected per connection
//                                  if(currentRdy >= expectedRdy && availableRdy > ceilingRdy)
//                                  ceilingRdy = availableRdy;
            final int newRdy = Math.min(ceilingRdy, currentRdy + 1);
            if (newRdy > currentRdy) {
                ChannelFuture future = con.command(new Rdy(newRdy));
                if(null != future)
                    future.addListener(new ChannelFutureListener() {
                        @Override
                        public void operationComplete(ChannelFuture channelFuture) throws Exception {
                            if (channelFuture.isSuccess()) {
                                int lastRdy = con.getCurrentRdyCount();
                                con.setCurrentRdyCount(newRdy);
                                conSet.addTotalRdy(newRdy - lastRdy);
                            }
                            latch.countDown();
                        }
                    });
            } else {
                latch.countDown();
            }
        } else {
            latch.countDown();
        }
    }

    private void mayDeclineRdy(final NSQConnection con, final ConnectionWrapperSet conSet, final CountDownLatch latch) {
        int currentRdy = con.getCurrentRdyCount();
        if (currentRdy > 1) {
            //update rdy
            final int expectedRdy = con.getExpectedRdy();
            final int newRdy = Math.min(currentRdy - 1, expectedRdy);
            ChannelFuture future = con.command(new Rdy(newRdy));
            if(null != future)
                future.addListener(new ChannelFutureListener() {
                    @Override
                    public void operationComplete(ChannelFuture channelFuture) throws Exception {
                        if (channelFuture.isSuccess()) {
                            int lastRdy = con.getCurrentRdyCount();
                            con.setCurrentRdyCount(newRdy);
                            conSet.addTotalRdy(newRdy - lastRdy);
                        }
                        //latch count down
                        latch.countDown();
                    }
                });
        } else {
            //latch count down
            latch.countDown();
        }
    }

    private void redistributeRdy(float scheduleLoad, boolean mayTimeout, final int rdyPerCon) {
       for(String topic:topic2Subs.keySet()) {
           final ConnectionWrapperSet subs = topic2Subs.get(topic);
           if(null != subs) {
               subs.writeLock();
               try {
                   final int latchCount = subs.size();
                   final CountDownLatch latch = new CountDownLatch(latchCount);
                   if (!mayTimeout && scheduleLoad <= THRESDHOLD && !subs.isBackoff()) {
                       for (NSQConnectionWrapper sub : subs) {
                           final NSQConnection con = sub.getConn();
                           exec.submit(new Runnable() {
                               @Override
                               public void run() {
                                   mayIncreaseRdy(con, rdyPerCon, subs, latch);
                               }
                           });
                       }
                   } else if ((scheduleLoad >= WATER_HIGH && mayTimeout) && !subs.isBackoff()) {
                       //rdy decrease
                       for (NSQConnectionWrapper sub : subs) {
                           final NSQConnection con = sub.getConn();
                           exec.submit(new Runnable() {
                               @Override
                               public void run() {
                                   mayDeclineRdy(con, subs, latch);
                               }
                           });
                       }
                   }
                   //await for may rdy updates
                   try {
                       if (!latch.await(latchCount * RDY_TIMEOUT, TimeUnit.MILLISECONDS)) {
                           logger.error("Timeout for redistribute connections rdy {}", topic);
                       }
                   } catch (InterruptedException e) {
                       logger.error("Interrupted while waiting for resume on all connections for {}", topic);
                   }
               } finally {
                   subs.writeUnlock();
               }
               if (System.currentTimeMillis() - subs.getLastProofread() > subs.getProofreadFactor() * PROOFREAD_INTERVAL) {
                   proofreadTotalRdy(topic);
               }
           }
       }
    }

    public Set<NSQConnectionWrapper> getSubscribeConnections(String topic) {
        return this.topic2Subs.get(topic);
    }
}
