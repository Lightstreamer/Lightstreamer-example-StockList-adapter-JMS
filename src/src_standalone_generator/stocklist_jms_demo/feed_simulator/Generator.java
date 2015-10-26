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
package stocklist_jms_demo.feed_simulator;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.HashMap;
import java.util.Properties;
import java.util.Random;

import javax.jms.JMSException;
import javax.jms.Message;
import javax.jms.TextMessage;
import javax.naming.NamingException;

import org.apache.log4j.Logger;
import org.apache.log4j.xml.DOMConfigurator;

import stocklist_jms_demo.common.ConnectionLoop;
import stocklist_jms_demo.common.ExtendedMessageListener;
import stocklist_jms_demo.common.FeedMessage;
import stocklist_jms_demo.common.HeartbeatMessage;
import stocklist_jms_demo.common.JMSHandler;
import stocklist_jms_demo.common.SubscribedItemAttributes;


public class Generator implements ExtendedMessageListener, ExternalFeedListener {


    private static Random randomGen = new Random();

    /**
     * Main method. Called by script.
     */
    public static void main(String[] args) {
        if (args == null || args.length < 1 || args[0] == null) {
            //if no arguments passed we exit. We need 1 parameter
            //containing the path of the configuration file
            System.out.println(noConf);
            return;
        }

        //read configuration file
        File configFile = new File(args[0]);
        Properties params = new Properties();
        try {
            params.load(new FileInputStream(configFile));
        } catch (FileNotFoundException e) {
            System.out.println(noConf);
            return;
        } catch (IOException e) {
            System.out.println(noConf);
            return;
        }

        //configure a log4j logger
        configureLogger(getParam(params,"logConf",true,null));

        logger.info("Stock generator is starting. Loading configuration...");

        //create our Generator class passing read parameters.
        new Generator(getParam(params,"jmsUrl",true,null),
                      getParam(params,"initialContextFactory",true,null),
                      getParam(params,"topicConnectionFactory",true,null),
                      getParam(params,"queueConnectionFactory",true,null),
                      getParam(params,"topicName",true,null),
                      getParam(params,"queueName",true,null),
                      getParam(params,"msgPoolSize",false,15),
                      getParam(params,"recoveryPauseMillis",false,2000));

        logger.info("Generator ready.");
    }

    /**
     * This object handles comunications with JMS.
     * Hides the use of Session, Connections, Publishers etc..
     */
    private JMSHandler jmsHandler;

    /**
     * This Map contains info about the subscribed items.
     */
    private HashMap<String,SubscribedItemAttributes> subscribedItems = new HashMap<String,SubscribedItemAttributes>();

    /**
     * This is the Simulator of the classic StockListDemo.
     */
    private ExternalFeedSimulator myFeed;

    private int msgPoolSize;
    private int recoveryPause;

    /**
     * A random id that represents the life of this generator.
     * It is sent within the heartbeat to let Lightstreamer distinguish
     * between 2 different lives of the Generator.
     * In production scenarios this would be probably substituted with
     * something more secure (i.e. it's not impossible to get 2 identical
     * randoms for 2 different Generator's lives).
     */
    private int random = -1;

    public Generator(String providerURL, String initialContextFactory, String topicConnectionFactory, String queueConnectionFactory, String topic, String queue, int msgPoolSize, int recoveryPause) {
        this.msgPoolSize = msgPoolSize;
        this.recoveryPause = recoveryPause;

        while (random == -1) {
            //-1 is a reserved value on the adapter
            random = randomGen.nextInt(1000);
        }

        //instantiate a JMSHandler
        jmsHandler = new JMSHandler(logger,initialContextFactory, providerURL,queueConnectionFactory, queue, topicConnectionFactory, topic);
        //This Generator will be the JMS listener
        jmsHandler.setListener(this);

        //instantiate and start the simulator. This is the object that "produce" data
        myFeed = new ExternalFeedSimulator();
        myFeed.start();
        //This Generator will be the listener
        myFeed.setFeedListener(this);

        //start the loop that tries to connect to JMS
        new ConnectionLoopTPQR(jmsHandler, recoveryPause, logger).start();

        new HeartbeatThread().start();

        logger.debug("Generator ready");
    }

    /////// MessageListener

    private static final String messageNoComp = "Message received was not compatible with this process. Maybe someone else sending messages? ";
    private static final String subUnexItem = "(Subscribing) Unexpected item: ";
    private static final String unsubUnexItem = "(Unsubscribing) Unexpected item: ";
    private static final String unsubUnexHandle = "(Unsubscribing) Unexpected handle for item: ";

    /**
     * receive messages from JMSHandler
     */
    public void onMessage(Message message) {
        String feedMsg = null;
        logger.debug("Message received: processing...");
        try {
            //pull out text from the Message object
            TextMessage textMessage = (TextMessage) message;
            feedMsg = textMessage.getText();
            logger.debug("Message:TextMessage received: " + feedMsg);
        } catch (ClassCastException cce) {
            //if message isn't a TextMessage then this update is not "correct"
            logger.warn(messageNoComp + "(ClassCastException)");
            return;
        } catch (JMSException jmse) {
            logger.error("Generator.onMessage - JMSException: " + jmse.getMessage());
            return;
        }

        String itemName = null;
        String handleId = null;
        if (feedMsg != null) {
            logger.debug("Recived message: " + feedMsg);
            if (feedMsg.equals("reset")) {
                reset();
            } if (feedMsg.indexOf("subscribe") == 0) {
                //this is a subscribe message
                itemName = feedMsg.substring(9,feedMsg.indexOf("_"));
                handleId = feedMsg.substring(feedMsg.indexOf("_")+1);
                subscribe(itemName,handleId);
            } else if (feedMsg.indexOf("unsubscribe") == 0) {
                //this is a unsubscribe message
                itemName = feedMsg.substring(11,feedMsg.indexOf("_"));
                handleId = feedMsg.substring(feedMsg.indexOf("_")+1);
                unsubscribe(itemName,handleId);
            }
        }

        if (itemName == null) {
            //the message isn't a valid message
            logger.warn(messageNoComp + "Message: " + feedMsg);
        }
    }

    private void reset() {
        synchronized (subscribedItems) {
            subscribedItems = new HashMap<String,SubscribedItemAttributes>();
        }
    }

    private void subscribe(String itemName, String handleId) {
        logger.debug("Subscribing " + itemName + "(" + handleId + ")");
        if (!itemName.startsWith("item")) {
            //item composed by "item" + ID: this is not a valid one
            logger.error(subUnexItem + itemName + "(" + handleId + ")");
            return;
        }
        try {
            int nI = Integer.parseInt(itemName.substring(4));
            if (nI > 30 || nI < 1) {
                //this item is not in the admitted range
                logger.error(subUnexItem + itemName + "(" + handleId + ")");
                return;
            }
        } catch (NumberFormatException nfe) {
            //non-numeric itemID not admitted
            logger.error(subUnexItem + itemName + "(" + handleId + ")");
            return;
        }

        logger.debug("(Subscribing) Valid item: " + itemName + "(" + handleId + ")");
        synchronized (subscribedItems) {
            //put the item in the subscribedItems map
            //if another subscription is already in that will be replaced
            SubscribedItemAttributes attr = new SubscribedItemAttributes(itemName,handleId);
            subscribedItems.put(itemName, attr);
        }
         // now we ask the feed for the snapshot; our feed will insert
         // an event with snapshot information into the normal updates flow
         myFeed.sendCurrentValues(itemName);
         logger.info("Subscribed " + itemName + "(" + handleId + ")");

    }

    private void unsubscribe(String itemName, String handleId) {
        logger.debug("Unsubscribing " + itemName + "(" + handleId + ")");
        synchronized (subscribedItems) {
            if (!subscribedItems.containsKey(itemName)) {
                //here checks are useless, just try to get the item from the
                //subscribedItems map, if not contained there is an error
                logger.error(unsubUnexItem + itemName + "(" + handleId + ")");
                return;
            }

            SubscribedItemAttributes sia = subscribedItems.get(itemName);
            if (sia.handleId == handleId) {
                //remove the item from the subscribedItems map.
                subscribedItems.remove(itemName);
            } else {
                logger.warn(unsubUnexHandle + itemName + "(" + handleId + ")");
            }

        }
        logger.info("Unsubscribed " + itemName + "(" + handleId + ")");
    }

    public void onException(JMSException arg0) {
        //we have lost the connection to JMS
        synchronized (subscribedItems) {
            //empty the subscribedItems map; this way, once reconnected
            //we are able to re-send snapshots
            subscribedItems = new HashMap<String, SubscribedItemAttributes>();
        }
        //and loop to try to reconnect
        new ConnectionLoopTPQR(jmsHandler, recoveryPause, logger).start();

    }

    /////////////// ExternalFeedListener

    /**
     * Receive update from the simulator.
     */
    public void onEvent(String itemName, HashMap currentValues, boolean isSnapshot) {
        SubscribedItemAttributes sia = null;
        synchronized (subscribedItems) {
            if (!subscribedItems.containsKey(itemName)) {
                //simulator always produce all updates. Here we filter
                //non-subscribed items
                return;
            }

            //handle the snapshot status
            sia = subscribedItems.get(itemName);


            if (!sia.isSnapshotSent) {
                if (!isSnapshot) {
                    // we ignore the update and keep waiting until
                    // a full snapshot for the item has been received
                    return;
                }
                //this is the snapshot update
                sia.isSnapshotSent = true;
            } else {
                if (isSnapshot) {
                    // it's not the first event we have received carrying
                    // snapshot information for the item; so, this event
                    // is not a snapshot from Lightstreamer point of view
                    isSnapshot = false;
                }
            }
        }

        //prepare the object to send through JMS
        FeedMessage toSend = new FeedMessage(itemName,currentValues,isSnapshot,sia.handleId,this.random);
        try {
            //publish the update to JMS
            jmsHandler.publishMessage(toSend);
        } catch (JMSException je) {
            logger.error("Unable to send message - JMSException:" + je.getMessage());
        }

    }

    ///////////// Utils

    private static String noConf = "Please specify a valid configuration file as parameter.\nProcess exits.\n";
    private static String noParam = " is missing.\nProcess exits";
    private static String useDefault = " is missing. Using default.";
    private static String noLog = "Log configuration fails. Check you configuration files\nProcess exits";
    private static String isNaN = " must be a number but it isn't. Using default.";

    private static Logger logger;

    private static int getParam(Properties params, String toGet, boolean required, int def) {
        int resInt;
        String res = params.getProperty(toGet);
        if (res == null) {
            if (required) {
                throw new IllegalStateException(toGet + noParam);
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
    private static String getParam (Properties params, String toGet, boolean required, String def) {
        String res = params.getProperty(toGet);
        if (res == null) {
            if (required) {
                throw new IllegalStateException(toGet + noParam);
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

    private static void configureLogger(String logConf) {
        if (logConf != null) {
            try {
                DOMConfigurator.configureAndWatch(logConf, 10000);
                logger = Logger.getLogger("SLGenerator");
            } catch (Exception ex) {
                ex.printStackTrace();
                System.out.println(ex.getMessage());
                throw new IllegalStateException(noLog);
            }
        } else {
            System.out.println(noLog);
            throw new IllegalStateException(noLog);
        }
    }


    ////////////////////// RecoveryThread

    private class ConnectionLoopTPQR extends ConnectionLoop {

        public ConnectionLoopTPQR(JMSHandler jmsHandler, int recoveryPause, Logger logger) {
            super(jmsHandler, recoveryPause, logger);
        }

        protected void onConnectionCall() {
            //nothing to do
            return;
        }

        protected void connectionCall() throws JMSException, NamingException {
            //initialize TopicPublisher and QueueReceiver
            jmsHandler.initTopicPublisher(msgPoolSize);
            jmsHandler.initQueueReceiver();
        }

    }

    ////////////////////// HeartbeatThread

    private class HeartbeatThread extends Thread {

        private HeartbeatMessage fixedMessage = new HeartbeatMessage(random);

        public void run() {
            while (true) {
                try {
                    //publish the update to JMS
                    jmsHandler.publishMessage(fixedMessage);
                } catch (JMSException je) {
                    logger.error("Unable to send message - JMSException:" + je.getMessage());
                }
                logger.debug("Heartbeat sent: " + fixedMessage.random);

                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                }
            }
        }
    }

}