# Lightstreamer - Stock-List Demo - Java (JMS) Adapter

<!-- START DESCRIPTION lightstreamer-example-stocklist-adapter-jms -->

This example shows how to integrate Lightstreamer Server with a <b>JMS (Java Message Service)</b> data feed.<br>
<b>For a more comprehensive solution, a separate product exists: the Lightstreamer JMS Gateway, which offers full-blown JMS APIs for JavaScript and other languages and does not require any server-side coding.</b><br>
<i>For any inquiry, please email info@lightstreamer.com.</i>

## Details

This application is the same as the [Lighstreamer - Basic Stock-List Demo - HTML Client](https://github.com/Weswit/Lightstreamer-example-Stocklist-client-javascript#basic-stock-list-demo---html-client), with the difference that the market-data-feed simulator is an external process that communicates with the Lightstreamer Data Adapter through JMS.
So, the goal of this demo is to show how a Lightstreamer Data Adapter can obtain data from an external source through JMS middleware. Both the Data Generator (feed process) and the Data Adapter source code are provided in this project.

### The Architecture

![Architecture](architecture.png)<br>

Referring to a Web-based scenario, the browser gets the static part of the page from a Web server, then connects to Lightstreamer Server to subscribe to the real-time updates. The Data Generator is a stand-alone process that simulates a data feed; it generates market prices for a set of stocks and
publishes this data, on demand, over JMS. The JMS Data Adapter communicates with the Data Generator through a JMS Server.<br>
The orange components in the architecture diagram above are provided as part of this example.<br>

The sequence diagram below shows a typical interaction between the components of the architecture. Notice that the method and message names are purely symbolic and are not directly referred to the APIs used in the source code.

![Sequence Diagram](flow_new.png)<br>

The workflow of the application is the following:
* The Browser retrieves the static web resources from a Web server (that could be Lightstreamer internal web server) and initiates a push session with Lightstreamer Server.
* The Data Adapter, in its subscribe() method implementation, sends a message to a JMS queue to request the generator to start publishing the real-time data for a certain item.
* The Data Generator reads a requests from the queue and publishes to a JMS topic the current values for the subscribed item (i.e., snapshot) and any subsequent updates.
* The Data Adapter receives updates through the JMS topic and injects them into the Lightstreamer Kernel, which, in turn, sends them to clients.<br>

<i>NOTE: To keep the code simple and clear, the demo does not include advanced fail-over and recovery mechanisms in the communication between the Adapter and the Generator.</i>

### Dig the Code

The `src` folder contains:
* `src_adapter`: contains the source code for the JMS Stock-List Demo Data Adapter. It can be referred to as a basic example for the development of Data Adapters based on Java Message Service (JMS).
* `src_generator`: contains the source code for the Generator.
* `src_commons`: contains the source code for classes used by both the Adapter and the Generator.

The `lib` folder should contain all the libraries needed for the Adapter recompilation; this includes:
* Lightstreamer java interface library (`ls-adapter-interface.jar` from the `/lib` folder of latest Lightstreamer distribution.);
* log4j library, used for the Adapter's own logging (`log4j-1.2.15.jar` from the `/shared/lib` folder of latest Lightstreamer distribution).

See the source code comments for further details.

The Metadata Adapter functionalities are absolved by the `LiteralBasedProvider` in [Lightstreamer - Reusable Metadata Adapters - Java Adapter](https://github.com/Weswit/Lightstreamer-example-ReusableMetadata-adapter-java), a simple full implementation of a Metadata Adapter, made available in Lightstreamer distribution. 

The project is comprised of source code and a deployment example. 

<!-- END DESCRIPTION lightstreamer-example-stocklist-adapter-jms -->

## Install

### JMS Setup

This demo needs a JMS infrastructure to run. You can choose whatever JMS middleware you prefer. In this example, we will refer to [TIBCO Enterprise Message Service(TM)](http://www.tibco.com/products/automation/messaging/enterprise-messaging/enterprise-message-service/default.jsp), [JBossMQ](https://community.jboss.org/wiki/JBossMQ), and [JBoss Messaging](https://community.jboss.org/wiki/JBossMessaging).<br>
Please download and install the JMS software, then: 

#### With TIBCO EMS
* Create one topic and one queue. Open the `queues.conf` and `topics.conf` located under `EMSHome/bin/` and append a line containing respectively `stocksQueue` and `stocksTopic` (without apexes).
* Look for `jms.jar` and `tibjms.jar` from `EMSHome/clients/java`. You will need to copy them when deploying the Adapter and the Generator.

#### With JBossMQ
* Create one topic and one queue. Open the `jbossmq-destinations-service.xml` file located under `/JBossHome/server/default/deploy/jms/` and add two mbean elements as shown below:

```xml
<mbean code="org.jboss.mq.server.jmx.Topic" name="jboss.mq.destination:service=Topic,name=stocksTopic">
  <depends optional-attribute-name="DestinationManager">
    jboss.mq:service=DestinationManager
  </depends>
</mbean>
<mbean code="org.jboss.mq.server.jmx.Queue" name="jboss.mq.destination:service=Queue,name=stocksQueue">
  <depends optional-attribute-name="DestinationManager">
    jboss.mq:service=DestinationManager
  </depends>
</mbean>
```

* Look for `jms.jar` and `jbossmq-client.jar` from `JBossHome/client/`. You will need to copy them when deploying the Adapter and the Generator.

#### With JBoss Messaging
* Create one topic and one queue. Open the `destinations-service.xml` file located under `/JBossHome/server/default/deploy/jboss-messaging.sar` and add two mbean elements as shown below:

```xml
<mbean code="org.jboss.jms.server.destination.TopicService" name="jboss.messaging.destination:service=Topic,name=stocksTopic" xmbean-dd="xmdesc/Topic-xmbean.xml">
  <depends optional-attribute-name="ServerPeer">
    jboss.messaging:service=ServerPeer</depends>
  <depends>jboss.messaging:service=PostOffice</depends>
</mbean>
<mbean code="org.jboss.jms.server.destination.QueueService" name="jboss.messaging.destination:service=Queue,name=stocksQueue" xmbean-dd="xmdesc/Queue-xmbean.xml">
  <depends optional-attribute-name="ServerPeer">
    jboss.messaging:service=ServerPeer</depends>
  <depends>jboss.messaging:service=PostOffice</depends>
</mbean>
```

* Look for `javassist.jar`, `jbossall-client.jar`, `jboss-messaging-client.jar`, `jnpserver.jar`, and `trove.jar` from `/JBossHome/client/`. Look for `jboss-aop-jdk50.jar` from `/JBossHome/server/default/deploy/jboss-aop-jdk50.deployer/`. Download the JMS SDK from
Sun's website and look for `jms.jar` in the lib folder. You will need to copy them when deploying the Adapter and the Generator.

<i>NOTE: From here on, this readme will refer to `tibjms.jar`, `jbossall-client.jar`, or the set of jars needed for JBoss Messaging as `customjmsjar.jar`.</i>

### Adapter Setup

If you want to install a version of this Adapter in your local environment, follow these steps:

* Download the `deploy.zip` file that you can find in the [deploy release](https://github.com/Weswit/Lightstreamer-example-StockList-adapter-JMS/releases) of this project and extract the `Deployment_LS/StockQuotesJMSAdapter` folder.
* Make sure you have installed Lightstreamer Server, as explained in the `GETTING_STARTED.TXT` file in the installation home directory.
* Make sure that Lightstreamer Server is not running.
* Copy the `StockQuotesJMSAdapter` directory and all of its files from this directory to the `adapters` subdirectory in your Lightstreamer Server installation home directory.
* Copy the `jms.jar` and `customjmsjar.jar` files under `StockQuotesJMSAdapter/lib` (or under `Lightstreamer/shared/lib` if you think that those jars will be used by other Adapters, too).
* Copy the `ls-generic-adapters.jar` file from the `lib` directory of the sibling "Reusable_MetadataAdapters" SDK example to the `shared/lib` subdirectory in your Lightstreamer Server installation home directory.
* Open and configure `StockQuotesJMSAdapter/adapters.xml` as done with the Generator configuration file (except this is an xml file while the other is a property file). You can also configure the logging category of the Adapter in `StockQuotesJMSAdapter/adapters_log_conf.xml`.
* Lightstreamer Server is now ready to be launched.

### Generator Setup

* Download the `deploy.zip` file that you can find in the [deploy release](https://github.com/Weswit/Lightstreamer-example-StockList-adapter-JMS/releases) of this project and extract the `Deployment_Generator` folder.
* Copy the contents of the `Generator` folder to any folder in your file system and put `jms.jar` and `customjmsjar.jar` under its `lib` subfolder.
* Configure the launch script `start_generator.bat` (or `start_generator.sh` if you are under Unix) setting the GENERATOR_HOME (the path of the folder), the JAVA_HOME (path of a JRE/JDK) and CONF_FILE (the path of a configuration file) variables.
* Create your configuration file. The `included test.conf` file shows all available parameters. Note that most parameters are required (you can omit msgPoolSize and recoveryPauseMillis).
* Create a `log4j` configuration file (see `log.xml` as an Example). The category used by the Generator is SLGenerator.

### Start the client

Please test your Adapter with one of the clients in this [list](https://github.com/Weswit/Lightstreamer-example-StockList-adapter-jms#clients-using-this-adapter).<br>

To make the StockListDemo front-end pages consult the newly installed Adapter Set, you need to modify the front-end pages and set the required Adapter Set name to STOCKLISTDEMO_JMS when creating the LightstreamerClient instance. So a line like this:
```js
var sharingClient = new LightstreamerClient(hostToUse,"DEMO");
```
becomes like this:
```js
var sharingClient = new LightstreamerClient(hostToUse,"STOCKLISTDEMO_JMS");
  ```
(Note: you don't need to reconfigure the Data Adapter name, as it is the same in both Adapter Sets).

Moreover, as the referred Adapter Set has changed, make sure that the front-end no longer shares the Connection with other demos.
So a line like this:
```js
sharingClient.connectionSharing.enableSharing("DemoCommonConnection","ls/","SHARE_SESSION", true);
```
should become like this:
```js
sharingClient.connectionSharing.enableSharing("JMSStockListConnection","ls/","SHARE_SESSION", true);
```
The StockListDemo web front-end is now ready to be opened. The front-end will now get data from the newly installed Adapter Set.

## Build

To build your own version of `LS_StockListJMS_DataAdapter.jar` and/or `StockQuotesGeneratorJMS`, instead of using the ones provided in the `deploy.zip` file from the Install section above, follow these steps:

* Get the `ls-adapter-interface.jar` and `log4j-1.2.15.jar` files from the [latest Lightstreamer distribution](http://www.lightstreamer.com/download) and put these files into lib folder.
* Create the jars `StockQuotesGeneratorJMS.jar` and `LS_StockListJMS_DataAdapter.jar` with commands like these:
```sh
 >javac -source 1.7 -target 1.7 -nowarn -g -classpath lib/log4j-1.2.15.jar;lib/ls-adapter-interface.jar;jms.jar;customjmsjar.jar -sourcepath src/src_adapter;src/src_commons -d tmp_classes src/src_adapter/stocklist_jms_demo/adapters/StockQuotesJMSDataAdapter.java
 
 >jar cvf LS_StockListJMS_DataAdapter.jar -C tmp_classes stocklist_jms_demo
 
 >javac -source 1.7 -target 1.7 -nowarn -g -classpath lib/log4j-1.2.15.jar;jms.jar;customjmsjar.jar -sourcepath src/src_standalone_generator;src/src_commons -d tmp_classes src/src_standalone_generator/stocklist_jms_demo/feed_simulator/Generator.java
 
 >jar cvf StockQuotesGeneratorJMS -C tmp_classes stocklist_jms_demo/feed_simulator
```

## See Also

### Clients Using This Adapter

<!-- START RELATED_ENTRIES -->

* [Lightstreamer - Stock-List Demos - HTML Clients](https://github.com/Weswit/Lightstreamer-example-Stocklist-client-javascript)

<!-- END RELATED_ENTRIES -->
### Related Projects
* [Lightstreamer - Reusable Metadata Adapters - Java Adapter](https://github.com/Weswit/Lightstreamer-example-ReusableMetadata-adapter-java)
* [Lightstreamer - Portfolio Demo - Java Adapter](https://github.com/Weswit/Lightstreamer-example-Portfolio-adapter-java)

### The Same Demo Adapter With Other Technologies
* [Lightstreamer - Stock-List Demo - Java Adapter](https://github.com/Weswit/Lightstreamer-example-StockList-adapter-java)
* [Lightstreamer - Stock-List Demo - .NET Adapter](https://github.com/Weswit/Lightstreamer-example-StockList-adapter-dotnet)

## Lightstreamer Compatibility Notes

* Compatible with Lightstreamer SDK for Java Adapters version 5.1.x
