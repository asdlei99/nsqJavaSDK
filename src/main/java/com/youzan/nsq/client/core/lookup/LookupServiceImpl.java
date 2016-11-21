package com.youzan.nsq.client.core.lookup;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.youzan.nsq.client.core.LookupAddressUpdate;
import com.youzan.nsq.client.entity.Address;
import com.youzan.nsq.client.entity.Partitions;
import com.youzan.nsq.client.entity.Role;
import com.youzan.nsq.client.entity.Topic;
import com.youzan.nsq.client.exception.NSQLookupException;
import com.youzan.util.NamedThreadFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * @author <a href="mailto:my_email@email.exmaple.com">zhaoxi (linzuxiong)</a>
 */
public class LookupServiceImpl implements LookupService {

    private static final Logger logger = LoggerFactory.getLogger(LookupServiceImpl.class);
    private static final long serialVersionUID = 1773482379917817275L;
    private static final String HTTP_PRO_HEAD = "http://";
    private static final String BROKER_QUERY_URL = "%s/lookup?topic=%s&access=%s";

    /**
     * JSON Tool
     */
    private static final ObjectMapper mapper = new ObjectMapper()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);


    private final Role role;
    /**
     * the sorted lookup's addresses
     */
    private volatile List<String> addresses;

    /**
     * Load-Balancing Strategy: round-robin
     */
    private volatile int offset = 0;
    private boolean started = false;
    private boolean closing = false;
    private volatile long lastConnecting = 0L;
    private final int _INTERVAL_IN_SECOND = 60;
    private final ScheduledExecutorService scheduler = Executors
            .newSingleThreadScheduledExecutor(new NamedThreadFactory("LookupChecker", Thread.MAX_PRIORITY));

    /**
     * @param addresses the lookup addresses
     * @param role
     */
    public LookupServiceImpl(final String[] addresses, Role role) {
        if (addresses == null || addresses.length == 0) {
            throw new IllegalArgumentException("Your input 'addresses' is blank!");
        }
        this.role = role;
        initAddresses(addresses);
    }

    private void initAddresses(final String[] addresses) {
        if (addresses == null || addresses.length == 0) {
            throw new IllegalArgumentException("Your input addresses is blank!");
        }
        //sort is comment out, given follwoing reasons:
        //1. sort performed when lookup address specified by user, in NSQConfig.setLookupAddresses()
        //2. sort performed when LookupAddressUpdate returns new lookup addresses
        //Collections.sort(addresses);
        this.addresses = Arrays.asList(addresses);
    }

    @Override
    public void start() {
        if (!started) {
            // begin: a light implement way
            started = true;
            offset = _r.nextInt(100);
            keepLookupServers();
        }
    }

    /**
     * @param addresses the new lookup addresses to update
     */
    private void updateAddresses(final List<String> addresses) {
        if(null == addresses || addresses.isEmpty() || this.addresses == addresses)
            return;
        this.addresses = addresses;
        //do not clear old addresses, as it may point to user specified lookup addresses.
    }

    /**
     * @return the sorted lookup's addresses
     */
    List<String> getAddresses() {
        return this.addresses;
    }

    /**
     * Asynchronized processing
     */
    private void keepLookupServers() {
        final int delay = _r.nextInt(60); // seconds
        scheduler.scheduleWithFixedDelay(new Runnable() {
            @Override
            public void run() {
                try {
                    newLookupServers();
                } catch (FileNotFoundException e) {
                    logger.warn("You run with the lower server version.");
                } catch (Exception e) {
                    logger.error("Exception", e);
                }
            }
        }, delay, _INTERVAL_IN_SECOND, TimeUnit.SECONDS);
    }

    private void newLookupServers() throws IOException {
        //fetch lookup addresses update
        List<String> newLookupAddrs =  LookupAddressUpdate.getInstance()
                .getLookupAddresses();
        updateAddresses(newLookupAddrs);

        List<String> currentAddresses = this.addresses;
        if(currentAddresses == null || currentAddresses.isEmpty())
            return;
        if (System.currentTimeMillis() < lastConnecting + TimeUnit.SECONDS.toMillis(_INTERVAL_IN_SECOND)) {
            return;
        }
        lastConnecting = System.currentTimeMillis();
        if (!this.started) {
            if (closing) {
                logger.warn("Having closed the lookup service.");
            }
            return;
        }
        final int index = ((offset++) & Integer.MAX_VALUE) % currentAddresses.size();
        String lookup = currentAddresses.get(index);
        if(!lookup.startsWith(HTTP_PRO_HEAD))
            lookup = HTTP_PRO_HEAD + lookup;
        final String url = String.format("%s/listlookup", lookup);
        logger.debug("Begin to get the new lookup servers. LB: Size: {}, Index: {}, From URL: {}",
                currentAddresses.size(), index, url);
        final JsonNode rootNode;
        JsonNode tmpRootNode = null;
        URL lookupUrl;
        try {
            lookupUrl = new URL(url);
            tmpRootNode = readFromUrl(lookupUrl);
        } catch (ConnectException ce) {
            //got a connection timeout exception(maybe), what we do here is:
            //1. record the ip&addr of both client and server side for trace debug.
            //2. TODO: improve timeout value of jackson parser to give it a retry, record
            //   a trace about the result, if failed, throws exception to interrupt
            //   lookup checker run().
            _handleConnectionTimeout(lookup, ce);
            return;
        } finally {
            //assign temp root node to rootNode, in both successful case and filed case
            rootNode = tmpRootNode;
        }
        final JsonNode nodes = rootNode.get("lookupdnodes");
        if (null == nodes) {
            logger.error("NSQ Server do response without any lookup servers!");
            return;
        }
        final List<String> newLookups = new ArrayList<>(nodes.size());
        for (JsonNode node : nodes) {
            final String host = node.get("NodeIP").asText();
            final int port = node.get("HttpPort").asInt();
            final String address = host + ":" + port;
            newLookups.add(address);
        }
        if (!newLookups.isEmpty()) {
            Collections.sort(newLookups);
            this.addresses = newLookups;
        }
        logger.debug("Recently have got the lookup servers : {}", this.addresses);
    }

    /**
     * request http GET for pass in URL, then parse response to json, some predefined
     * header properties are added here, like Accept: application/vnd.nsq;
     * stream as json
     *
     * @param url url of json resource
     * @return jsonNode
     */
    private JsonNode readFromUrl(final URL url) throws IOException {
        logger.debug("Prepare to open HTTP Connection...");
        HttpURLConnection con = (HttpURLConnection) url.openConnection();
        con.setConnectTimeout(5 * 1000);
        con.setReadTimeout(10 * 1000);
        //skip that, as GET is default operation
        //add request header, to support nsq of new version
        con.setRequestProperty("Accept", "application/vnd.nsq; version=1.0");
        if (logger.isDebugEnabled()) {
            logger.debug("Request to {} responses {}:{}.", url.toString(), con.getResponseCode(), con.getResponseMessage());
        }
        //jackson handles InputStream close operation
        return mapper.readTree(con.getInputStream());
    }

    private void _handleConnectionTimeout(String lookup, ConnectException ce) throws IOException {
        String ip = "EMPTY", address = "EMPTY";
        try {
            InetAddress addr = InetAddress.getLocalHost();
            ip = addr.getHostAddress();//ip where sdk resides
            address = addr.getHostName();//address where sdk resides
        }catch(Exception e){
            logger.error("Could not fetch ip or address form local client, should not occur.", e);
        }
        logger.warn("Fail to connect to NSQ lookup. SDK Client, ip:{} address:{}. Remote lookup:{}. Will kick off another try in some seconds.", ip, address, lookup);
        logger.warn("Nested connection exception stacktrace:", ce);
    }

    @Override
    public Partitions lookup(Topic topic) throws NSQLookupException {
        switch (this.role) {
            case Consumer: {
                return lookup(topic, false);
            }
            case Producer: {
                return lookup(topic, true);
            }
            default: {
                throw new UnsupportedOperationException("Unknown options. Writable or Readable?");
            }
        }
    }

    @Override
    public Partitions lookup(final Topic topic, boolean writable) throws NSQLookupException{
        if (null == topic || null == topic.getTopicText() || topic.getTopicText().isEmpty()) {
            throw new NSQLookupException("Your input topic is blank!");
        }
        assert null != this.addresses;
        /*
         * It is unnecessary to use Atomic/Lock for the variable
         */
        final int index = ((offset++) & Integer.MAX_VALUE) % this.addresses.size();
        String lookup = this.addresses.get(index);
        if(!lookup.startsWith(HTTP_PRO_HEAD))
            lookup = HTTP_PRO_HEAD + lookup;
        final String url = String.format(BROKER_QUERY_URL, lookup, topic.getTopicText(), writable ? "w" : "r");
        logger.debug("Begin to lookup some DataNodes from URL {}", url);
        Partitions aPartitions = new Partitions(topic);

        try {
            final JsonNode rootNode = readFromUrl(new URL(url));
            long start = 0L;
            if(logger.isDebugEnabled())
                start = System.currentTimeMillis();
            final JsonNode partitions = rootNode.get("partitions");
            List<Address> unPartitionedDataNodes = new ArrayList<>();
            List<Address> partitionedDataNodes = new ArrayList<>();
            Set<Address> partitionNodeSet = new HashSet<>();
            if (null != partitions) {
                Iterator<String> irt = partitions.fieldNames();
                int partitionCount = 0;
                while (irt.hasNext()) {
                    String parId = irt.next();
                    int parIdInt = Integer.valueOf(parId);
                    JsonNode partition = partitions.get(parId);
                    final Address address = createAddress(partition);
                    if(parIdInt >= 0) {
                        partitionedDataNodes.add(address);
                        partitionNodeSet.add(address);
                        partitionCount++;
                    }
                }
                aPartitions.updatePartitionDataNode(partitionedDataNodes.toArray(new Address[0]), partitionCount);
                if(logger.isDebugEnabled()){
                    logger.debug("SDK took {} mill sec to create mapping for partition.", (System.currentTimeMillis() - start));
                }
            }

            //producers part in json
            final JsonNode producers = rootNode.get("producers");
            for (JsonNode node : producers) {
                final Address address = createAddress(node);
                if(!partitionNodeSet.contains(address))
                    unPartitionedDataNodes.add(address);
            }

            aPartitions.updateUnpartitionedDataNodea(unPartitionedDataNodes.toArray(new Address[0]));
            logger.debug("The server response info after looking up some DataNodes: {}", rootNode.toString());
            return aPartitions; // maybe it is empty
        } catch (Exception e) {
            final String tip = "SDK can't get the right lookup info. " + url;
            throw new NSQLookupException(tip, e);
        }
    }

    /**
     * create nsq broker Address, using pass in JsonNode,
     * specidied key in function will be used to extract
     * field to construct Address
     * @return Address nsq broker Address
     */
    private Address createAddress(JsonNode node){
        final String host = node.get("broadcast_address").asText();
        final int port = node.get("tcp_port").asInt();
        final String version = node.get("version").asText();
        return new Address(host, port, version);
    }

    @Override
    public void close() {
        closing = true;
        scheduler.shutdownNow();
    }
}