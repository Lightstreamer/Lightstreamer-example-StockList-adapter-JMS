/*
 *
 * Copyright (c) Lightstreamer Srl
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */
package com.lightstreamer.stocklist_jms_demo.adapters;

import java.io.File;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import javax.jms.JMSException;
import javax.jms.Message;
import javax.jms.ObjectMessage;
import javax.naming.NamingException;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.lightstreamer.stocklist_jms_demo.common.ConnectionLoop;
import com.lightstreamer.stocklist_jms_demo.common.ExtendedMessageListener;
import com.lightstreamer.stocklist_jms_demo.common.FeedMessage;
import com.lightstreamer.stocklist_jms_demo.common.HeartbeatMessage;
import com.lightstreamer.stocklist_jms_demo.common.JMSHandler;
import com.lightstreamer.stocklist_jms_demo.common.SubscribedItemAttributes;

import com.lightstreamer.adapter.common.InitException;
import com.lightstreamer.adapter.common.Mode;
import com.lightstreamer.adapter.data.DataAdapter;
import com.lightstreamer.adapter.data.ItemListener;
import com.lightstreamer.adapter.data.ItemManager;
import com.lightstreamer.adapter.data.SubscriptionException;

public class StockQuotesJMSDataAdapter implements DataAdapter, ExtendedMessageListener {

    /**
     * Private logger; a specific "LS_demos_Logger.StockQuotesJMS" category
     * should be supplied by log4j configuration.
     */
    private Logger logger;

    private JMSHandler jmsHandler;

    private ConcurrentHashMap<String,SubscribedItemAttributes> subscribedItems = new ConcurrentHashMap<String,SubscribedItemAttributes>();

    private volatile int nextHandleId = 1;

    private int msgPoolSize;
    private int recoveryPause;

    //a read/write lock is used
    private ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock(false);

    /**
     * The queue of pending requests for the Generator.
     */
    private ConcurrentLinkedQueue<String> toSendRequests = new ConcurrentLinkedQueue<String>();

    /*
     * Status variables. This adapter has 3 possible states:
     * 1) jmsOk=false and lastHeartbeatRandom=-1:
     *    no connection to JMS is available;
     * 2) jmsOk=true and lastHeartbeatRandom=-1:
     *    the adapter is connected to JMS but the Generator is not running;
     * 3) jmsOk=true and lastHeartbeatRandom!=-1:
     *    connection to JMS is established and the adapter is receiving
     *    heartbeats (and/or data) by the Generator.
     */
    private volatile boolean jmsOk = false;
    private int lastHeartbeatRandom = -1;

    /**
     * A simple counter to know if any updates/heratbeats
     * were received since the last check.
     */
    private int heartbeatCount = 0;

    /**
     * This map will update every subscribed item setting
     * the item_status field to inactive.
     */
    private final HashMap<String,String> inactiveMap = new HashMap<String,String>();

    /**
     * This map will update every subscribed item setting
     * the item_status field to inactive.
     * This map is used in case of snapshot updates.
     */
    private final HashMap<String,String> completeInactiveMap = new HashMap<String,String>();
    {
        inactiveMap.put("item_status","inactive");

        completeInactiveMap.put("stock_name","-");
        completeInactiveMap.put("time","0");
        completeInactiveMap.put("last_price","0");
        completeInactiveMap.put("ask","0");
        completeInactiveMap.put("bid","0");
        completeInactiveMap.put("bid_quantity","0");
        completeInactiveMap.put("ask_quantity","0");
        completeInactiveMap.put("pct_change","0");
        completeInactiveMap.put("min","0");
        completeInactiveMap.put("max","0");
        completeInactiveMap.put("ref_price","0");
        completeInactiveMap.put("open_price","0");
        completeInactiveMap.put("item_status","inactive");
    }


    ////////////////// DataAdapter

    /**
     * Called by Lightstreamer Kernel on start.
     */
    public void init(Map params, File configDir) throws InitException {
        /*
        String logConfig = (String) params.get("log_config");
        if (logConfig != null) {
            File logConfigFile = new File(configDir, logConfig);
            String logRefresh = (String) params.get("log_config_refresh_seconds");
            if (logRefresh != null) {
                DOMConfigurator.configureAndWatch(logConfigFile.getAbsolutePath(), Integer.parseInt(logRefresh) * 1000);
            } else {
                DOMConfigurator.configure(logConfigFile.getAbsolutePath());
            }
        }*/
        //get the logger
        logger = LogManager.getLogger("LS_demos_Logger.StockQuotesJMS");

        //load JMS connections parameters
        String providerURL = getParam(params,"jmsUrl",true,null);
        String initialContextFactory = getParam(params,"initialContextFactory",true,null);
        String topicConnectionFactory = getParam(params,"topicConnectionFactory",true,null);
        String queueConnectionFactory = getParam(params,"queueConnectionFactory",true,null);
        String topic = getParam(params,"topicName",true,null);
        String queue = getParam(params,"queueName",true,null);
        //the size of the message pool
        this.msgPoolSize = getParam(params,"msgPoolSize",false,15);
        //in case of disconnection/failed_connection from/to JMS this is
        //the pause between each reconnection attempt
        this.recoveryPause = getParam(params,"recoveryPauseMillis",false,2000);

        logger.debug("Configuration read.");

        //create the JMS handler. The object will handle the instantiation of JMS-related objects
        jmsHandler = new JMSHandler(logger,initialContextFactory, providerURL,queueConnectionFactory, queue, topicConnectionFactory, topic);
        //the message listener that will receive JMS messages will be the StockQuotesJMSDataAdapter instance (this)
        jmsHandler.setListener(this);

        //this thread keeps on trying to connect to JMS until succedes. When connected
        //calls the onConnection method
        new ConnectionLoopTSQS(jmsHandler, recoveryPause, logger).start();

        logger.info("StockQuotesJMSDataAdapter ready.");
    }

    @Override
    public CompletionStage<ItemManager> getItemManager(String itemName) throws SubscriptionException {
        if (!isValidItem(itemName)) {
            throw new SubscriptionException("(Subscribing) Unexpected item: " + itemName);
        }
        return CompletableFuture.completedFuture(new StockItemManager(itemName));
    }

    @Override
    public void shutdown() {
    }

    /**
     * Checks for valid item names (item1 item2 item3....item30).
     */
    private boolean isValidItem(String itemName) {
        if (!itemName.startsWith("item")) {
            return false;
        } else {
            try {
                int nI = Integer.parseInt(itemName.substring(4));
                if (nI > 30 || nI < 1) {
                    return false;
                }
            } catch (NumberFormatException nfe) {
                return false;
            }
        }
        return true;
    }

    //////////////////// ItemManager

    private class StockItemManager implements ItemManager {

        private final String itemName;

        StockItemManager(String itemName) {
            this.itemName = itemName;
        }

        @Override
        public Mode getSupportedMode() {
            return Mode.MERGE;
        }

        /**
         * Called by Lightstreamer Kernel on item subscription.
         */
        @Override
        public CompletionStage<Void> subscribe(ItemListener listener) {
            logger.info("Subscribing to " + itemName);

            logger.debug("(Subscribing) Valid item: " + itemName);

            //Generate an unique ID to represent the item subscription. This ID will be then
            //sent to the Generator which in turn will return it on each item update so that
            //the correct listener can be identified.
            String uniqueId = String.valueOf(nextHandleId++);

            //create an object to contain some basic attributes for the item
            //this item will be useful for snapshot handling and unsubscription calls
            SubscribedItemAttributes itemAttrs = new SubscribedItemAttributes(itemName,uniqueId);
            itemAttrs.listener = listener;

            //get the writelock to write inside the maps
            rwLock.writeLock().lock();
            logger.debug("------------------>Write LOCK 1");
                //insert item in the list of subscribed items
                //the item name will be the key, the attributes-object will be the value,
                subscribedItems.put(itemName, itemAttrs);

                boolean dispatchThread = false;
                if (lastHeartbeatRandom == -1) {
                    //JMS is not available now, send the inactive flag to the clients
                    //since this call is non-blocking we can issue it here
                    dispatchInactiveFlag(itemAttrs);
                } else {
                    //insert the subscription request to be dispatched to the Generator via JMS.
                    //This request asks the Simulator to start dispatching the data flow
                    //for this item. Note that it "enables" Generator to send data for this item
                    //and not to "generate" values for this item. In fact, Generator begins the
                    //production of values for all the items on startup.
                    toSendRequests.offer("subscribe"+itemName+"_"+uniqueId);
                    dispatchThread = true;
                }

            //release the lock
            logger.debug("------------------>Write UNLOCK 1");
            rwLock.writeLock().unlock();

            logger.debug("(Subscribing) Inserted in subscribed items list: " + itemName + " ("+uniqueId+")");

            if (dispatchThread) {
                //Start a thread to send the subscribe request to the Generator.
                //We should use something better like a pool of threads here, but for simplicity we start a new
                //thread each time we place some new request to be sent to the Generator inside the queue
                new SenderThread().start();
            }

            return CompletableFuture.completedFuture(null);
        }

        /**
         * Called by Lightstreamer Kernel to know if the snapshot
         * is available for an item.
         */
        @Override
        public boolean isSnapshotAvailable() {
            //we generate the updates so we can make snapshots always available (even if JMS is down
            //we generate locally an "inactive" update)
            return true;

            //Note that if the adapter does not know the schema for the items, here we should return true
            //only if data from the Generator is available:

            // return (lastHeartbeatRandom != -1);
        }

        /**
         * Called by Lightstreamer Kernel on item unsubscription.
         */
        @Override
        public CompletionStage<Void> unsubscribe() {
            logger.info("Unsubscribing from " + itemName);

            //get the writelock to check if the item is subscribed
            //and to eventually delete it
            rwLock.writeLock().lock();
            logger.debug("------------------>Write LOCK 2");
                //check if this is a subscribed item.
                if (!subscribedItems.containsKey(itemName)) {
                    //before logging must release the lock
                    logger.debug("------------------>Write UNLOCK 2");
                    rwLock.writeLock().unlock();
                    //not subscribed item, log a warning
                    logger.warn("(Unsubscribing) Unexpected item: " + itemName);
                    return CompletableFuture.completedFuture(null);
                }
                //get the object representing the unsubscribing item
                SubscribedItemAttributes item = subscribedItems.get(itemName);
                //remove the item from the subscribed items map
                subscribedItems.remove(itemName);

                boolean dispatchThread = false;
                if (lastHeartbeatRandom != -1) {
                    //insert the unsubscription request to be dispatched to the Generator via JMS.
                    //This request asks the Simulator to stop dispatching the data flow
                    //for this item (while the Generator keeps on producing the updates without
                    //publishing them over JMS)
                    toSendRequests.offer("unsubscribe"+itemName+"_"+item.handleId);
                    dispatchThread = true;
                }

            //release the lock
            logger.debug("------------------>Write UNLOCK 2");
            rwLock.writeLock().unlock();

            logger.debug("(Unsubscribing) removed from subscribed items list:" + itemName + " (" + item.handleId + ")");

            if (dispatchThread) {
                //Start a thread to send the unsubscribe request to the Generator.
                //We should use something better like a pool of threads here, but for simplicity we start a new
                //thread each time we place some new request to be sent to the Generator inside the queue
                new SenderThread().start();
            }

            return CompletableFuture.completedFuture(null);
        }
    }

    /**
     * Called to dispatch the inactive update to a subscribed item.
     * Two considerations are needed:
     * 1-This method should be better implemented in an asyncronous manner
     *   (i.e. put the updates in a queue and wait for a thread that dequeues
     *   it). In that case, also the updates received from the onMessage
     *   event should be queued in the same way.
     * 2-As this method is always called by a method that already owns the
     *   write lock, we don't get any lock here, (it would be better if there
     *   was a test here that gets a lock if the running thread doesn't own one).
     */
    private void dispatchInactiveFlag(SubscribedItemAttributes item) {
        //get the information about the snapshot status of the item (i.e. whether was already sent or not)
        boolean isSnapshot = !item.isSnapshotSent;
        if (isSnapshot) {
            item.isSnapshotSent = true;
        }

        ItemListener itemListener = item.listener;
        if (itemListener != null) {
            if (isSnapshot) {
                //send a complete snapshot with empty fields (apart from the item_status event set to "inactive")
                itemListener.snapshot(completeInactiveMap, null);
                //Note that if the adapter does not know the schema for the items, here we should send an incomplete
                //update with the isSnapshot flag set to false (look at isSnapshotAvailable method's comments)
            } else {
                //send an update containing only the item_status field set to "inactive"
                itemListener.update(inactiveMap);
            }
        }
        logger.debug("Inactive flag dispatched: " + item.itemName + " (" + item.handleId + ")");

    }

    ///////////MessageListener

    private static final String noCompMex = "Message received was not compatible with this adapter. Maybe someone else sending messages?";

    /**
     * Called by ConnectionLoop on connection with JMS.
     */
    public void onConnection() {
        //get the write lock to set the jmsOk flag to true
        rwLock.writeLock().lock();
        logger.debug("------------------>Write LOCK 3");
            logger.info("JMS is now up");
            //JMS connection is now up
            jmsOk = true;
        //release the lock
        logger.debug("------------------>Write UNLOCK 3");
        rwLock.writeLock().unlock();
    }


    /**
     * As this method is always called by a method that already owns the
     * write lock, we don't get any lock here (it would be better if there
     * was a test here that gets a lock if the running thread doesn't own one).
     */
    public void subscribeAll() {
        //we have to request the Generator all the currently subscribed items. Just before we send a reset message
        //to stop the Generator to send old items (since the Generator has no logic to know if Lightstreamer is
        //up or down, if Lightstreamer falls and then gets back alive the Generator could have items subscribed by the
        //old life of this adapter)

        logger.debug("Subscribing all to the Generator");
        //Any previous request will be reissued so we have to clear the queue in order to avoid
        //duplicate requests.
        toSendRequests.clear();
        //send a reset message to shut down all possible old subscription
        toSendRequests.offer("reset");
        //iterate through the subscribedItem to issue one subscription request per each subscribed item
        Enumeration<SubscribedItemAttributes> subItems = subscribedItems.elements();
        while(subItems.hasMoreElements()) {
            SubscribedItemAttributes sia = subItems.nextElement();
            //put the subscription request inside the queue
            toSendRequests.offer("subscribe"+sia.itemName+"_"+sia.handleId);
        }

        //Start a thread to send the subscribe request to the Generator.
        //We should use something better like a pool of threads here, but for simplicity we start a new
        //thread each time we place some new request to be sent to the Generator inside the queue
        new SenderThread().start();
    }

    /**
     * Called whenever the connection to JMS is lost.
     */
     public void onException(JMSException je) {
        logger.error("onException: JMSException -> " + je.getMessage());

        //get the write lock in order to set the jmsOk flag and to call the onFeedDisconnection method
        rwLock.writeLock().lock();
        logger.debug("------------------>Write LOCK 4");
            logger.info("JMS is now down");
            //when the JMS connection is lost, obviously also the connection with the Generator is
            this.onFeedDisconnection();
            //set jmsOk to false, we are no more connected with JMS
            jmsOk = false;
        logger.debug("------------------>Write UNLOCK 4");
        //release the lock
        rwLock.writeLock().unlock();

        //start loop to try to reconnect
        new ConnectionLoopTSQS(jmsHandler, recoveryPause, logger).start();

    }

    /**
     * Called in case the feed is lost (i.e. Generator is down or JMS
     * connection is down).
     * As this method is always called by a method that already owns the
     * write lock, we don't get any lock here, (it would be better if there
     * was a test here that gets a lock if the running thread doesn't own one).
     */
    public void onFeedDisconnection() {
        logger.info("Feed no more available");
        //set lastHeartbeatRandom to -1, ie we are no more connected with the Generator
        lastHeartbeatRandom = -1;
        //we iterates through the subscribedItem to send the "incative" field per each subscribed item
        Enumeration<SubscribedItemAttributes> subItems = subscribedItems.elements();
        while(subItems.hasMoreElements()) {
            SubscribedItemAttributes sia = subItems.nextElement();
            this.dispatchInactiveFlag(sia);
        }
    }

    /**
     * Receives messages from JMS.
     */
    public void onMessage(Message message) {
        if (message == null) {
            logger.warn(noCompMex + " (null)");
            return;
        }
        logger.debug("Received message");
        //we have to extract data from the Message object
        FeedMessage feedMsg = null;
        SubscribedItemAttributes item = null;
        try {
            //cast Message to ObjectMessage.
            ObjectMessage objectMessage = (ObjectMessage) message;
            //Obtain the contained Serializable object
            //try to cast it to HeartbeatMessage
            try {
                HeartbeatMessage beat = (HeartbeatMessage) objectMessage.getObject();
                handleHeartbeat(beat.random);
                return;
            } catch(ClassCastException jmse) {
                //not an Heartbeat, try to cast it to FeedMessage
                feedMsg = (FeedMessage) objectMessage.getObject();
                //test the contained heartbeat (the Generator could avoid to send the HeartbeatMessage if in
                //the last second a FeedMessage was sent)
                if (!handleHeartbeat(feedMsg.random)) {
                    return;
                }
            }
            logger.debug("Valid message");
        } catch (ClassCastException jmse) {
            //if message isn't an ObjectMessage or message.getObject() isn't a FeedMessage
            //then this update is not "correct"
            logger.warn(noCompMex + "(no FeedMessage instance)");
            return;
        } catch (JMSException jmse) {
            logger.error("StockQuotesJMSDataAdapter.onMessage - JMSException: " + jmse.getMessage(), jmse);
            return;
        }


        //handle the update
        //get the read lock to access the subscribedItems map and the handles map
        rwLock.readLock().lock();
        logger.debug("------------------>Read LOCK 5");
            //It discards updates about items that are no more subscribed.
            if (!subscribedItems.containsKey(feedMsg.itemName)) {
                //maybe the unsubscription message was lost?
                //or someone else is publishing updates?
                //or just a timing problem with the network?

                //release the lock and exit
                logger.debug("------------------>Read UNLOCK 5");
                rwLock.readLock().unlock();
                logger.debug("Received update for not subscribed item: "+ feedMsg.itemName);
                return;
            }

            ItemListener itemListener = null;
            boolean isSnapshot = false;

            //get the object that represents the item
            item = subscribedItems.get(feedMsg.itemName);
            if (item != null) {
                //check that the handle id matches the current subscription
                if (!item.handleId.equals(feedMsg.handleId)) {
                    //if the handle id does not match it means that an unsubscription and a
                    //subsequent new subscription were issued by Lightstreamer Kernel and
                    //that this update is related to the old subscription, so even if the
                    //update could be valid, we choose to discard it

                    //release the lock and exit
                    logger.debug("------------------>Read UNLOCK 5");
                    rwLock.readLock().unlock();
                    logger.debug("Received update for unsubscribed handle: " + feedMsg.itemName + "(" + feedMsg.handleId + ")");
                    return;
                }
                itemListener = item.listener;

                //Since the generator always sends a complete update (i.e. it does not filter unchanged values)
                //we can handle the snapshot flag on the adapter side:
                //the feedMessage carries a isSnapshot flag but we ignore that flag and set the isSnapshot flag
                //from the point of view of Lightstreamer Kernel
                if (!item.isSnapshotSent) {
                    item.isSnapshotSent = true;
                    isSnapshot = true;
                }
            }
            logger.debug("Received update for item " + feedMsg.itemName);

            // forward the update to Lightstreamer kernel
            if (isSnapshot) {
                itemListener.snapshot(feedMsg.currentValues, null);
            } else {
                itemListener.update(feedMsg.currentValues);
            }
        //release the lock
        logger.debug("------------------>Read UNLOCK 5");
        rwLock.readLock().unlock();

     }

    /**
     * Called on each message received from JMS.
     */
    private boolean handleHeartbeat(int beat) {
        rwLock.writeLock().lock();
        logger.debug("------------------>Write LOCK 6");
            if (lastHeartbeatRandom == beat) {
                //the heartbeat is correct, we increase a counter
                heartbeatCount = (heartbeatCount+1)%1000;
                logger.debug("Received heartbeat: " + beat);
                //release the lock
                logger.debug("------------------>Write UNLOCK 6");
                rwLock.writeLock().unlock();
                return true;
            } else {
                //this is the first heartbeat received from this Generator's life (or the first one
                //after a Generator's connectivity problem)
                logger.debug("Received NEW heartbeat: " + beat +", feed is now available" );
                //sets the new Heartbeat ID
                lastHeartbeatRandom = beat;
                //subscribe to all the subscribedItems towards the Generator
                this.subscribeAll();
                //reset the heartbeat counter
                heartbeatCount = 0;
                //release the lock
                logger.debug("------------------>Write UNLOCK 6");
                rwLock.writeLock().unlock();
                //start the new HeartbeatThread that will control that at least an heartbeat
                //was received in the last 2 seconds.
                new HeartbeatThread(beat).start();
                return false;
            }
    }

    private class HeartbeatThread extends Thread {

        private int random;
        private int count;

        public HeartbeatThread(int random) {
            this.random = random;
        }

        public void run() {
            //this thread will go on until the heartbeat ID is changed
            while (this.random == lastHeartbeatRandom) {
                //waits 2 seconds (i.e. we check the counter each 2 seconds)
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException e) {
                }
                //get the lock
                rwLock.writeLock().lock();
                logger.debug("------------------>Write LOCK 7");
                    if (this.random == lastHeartbeatRandom && count == heartbeatCount) {
                        logger.debug("2 Seconds without Heartbeats: " + this.random);
                        //the heartbeat is the same that we have to check, but no heartbeat arrived
                        //in the last two second. We consider the Generator down.
                        onFeedDisconnection();
                        //release the lock and end the thread
                        logger.debug("------------------>Write UNLOCK 7");
                        rwLock.writeLock().unlock();
                        return;
                    } else {
                        //at least 1 heartbeat arrived in the last 2 seconds
                        count = heartbeatCount;
                    }
                //release the lock
                logger.debug("------------------>Write UNLOCK 7");
                rwLock.writeLock().unlock();


            }
        }

    }


    ///////////////// Utils

    private static final String noParam = " is missing.\nProcess exits";
    private static final String useDefault = " is missing. Using default.";
    private static final String isNaN = " must be a number but it isn't. Using default.";

    private int getParam(Map params, String toGet, boolean required, int def) throws InitException {
        int resInt;
        String res = (String) params.get(toGet);
        if (res == null) {
            if (required) {
                throw new InitException(toGet + noParam);
            } else {
                if (logger != null) {
                    logger.warn(toGet + useDefault);
                }
                resInt = def;
            }
        } else {
            try {
                resInt = Integer.parseInt(res);
            } catch (NumberFormatException nfe) {
                if (logger != null) {
                    logger.error(toGet + isNaN);
                }
                resInt = def;
            }
        }

        if (logger != null) {
            logger.debug(toGet+ ": " + resInt);
        }
        return resInt;
    }

    private String getParam (Map params, String toGet, boolean required, String def) throws InitException {
        String res = (String) params.get(toGet);
        if (res == null) {
            if (required) {
                throw new InitException(toGet + noParam);
            } else {
                if (logger != null) {
                    logger.warn(toGet + useDefault);
                }
                res = def;
            }
        }
        if (logger != null) {
            logger.debug(toGet+ ": " + res);
        }
        return res;
    }

    //////////////////// ConnectionLoop

    private class ConnectionLoopTSQS extends ConnectionLoop {

        public ConnectionLoopTSQS(JMSHandler jmsHandler, int recoveryPause, Logger logger) {
            super(jmsHandler, recoveryPause, logger);
        }

        protected void onConnectionCall() {
            //call the connection handler
            onConnection();
        }

        protected void connectionCall() throws JMSException, NamingException {
            //initialize TopicSubscriber and QueueSender
            jmsHandler.initTopicSubscriber();
            jmsHandler.initQueueSender(msgPoolSize);
        }

    }

    public class SenderThread extends Thread {

        public void run() {
            String nextRequest = "";

            logger.debug("Dispatch thread started");
            //get the read lock to read the toSendRequests queue
            rwLock.readLock().lock();
            logger.debug("------------------>Read LOCK 8");
                //go on until there are requests in the queue
                while ((nextRequest = toSendRequests.poll()) != null) {
                    try {
                        //send message to the feed through JMS
                        jmsHandler.sendMessage(nextRequest);
                        logger.debug("Message dispatched to JMS: " + nextRequest);
                    } catch (JMSException je) {
                        logger.error("Can't actually dispatch request " + nextRequest + ": JMSException -> " + je.getMessage());
                    }
                }
            //release the lock
            logger.debug("------------------>Read UNLOCK 8");
            rwLock.readLock().unlock();
            logger.debug("Dispatch thread ends");
        }

    }
}
