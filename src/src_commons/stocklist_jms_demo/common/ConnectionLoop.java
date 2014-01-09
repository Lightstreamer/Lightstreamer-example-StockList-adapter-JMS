/*
 *
 * Copyright 2014 Weswit s.r.l.
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
package stocklist_jms_demo.common;



import javax.jms.JMSException;
import javax.naming.NamingException;

import org.apache.log4j.Logger;

//loop until connected to JMS
public abstract class ConnectionLoop extends Thread {

    private JMSHandler jmsHandler;
    private int recoveryPause;
    private Logger logger;

    private int localPhase;
    private static volatile int phase;


    public ConnectionLoop (JMSHandler jmsHandler, int recoveryPause, Logger logger) {
        this.jmsHandler = jmsHandler;
        this.recoveryPause = recoveryPause;
        this.logger = logger;
        this.localPhase = ++phase;
    }

    public void run() {

        boolean loop = false;
        do {
            if (this.localPhase != phase) {
                return;
            }
            loop = false;
            //reset JMSHandler
            jmsHandler.reset();
            try {
                //call the concrete method
                connectionCall();

            } catch(JMSException je) {
                //JMS not yet available
                //problems on connecting to JMS. We keep on trying
                //to reach JMS while the Server goes on
                logger.error("JMSException: " + je.getMessage(), je);
                loop = true;
            } catch (NamingException ne) {
                //at least one name is wrong. We keep on trying, in the case
                //that the wrong name will subsequently become valid
                logger.error("NamingException: " + ne.getMessage(), ne);
                loop = true;
            }

            if (loop && this.localPhase == phase) {
                try {
                    Thread.sleep(recoveryPause);
                } catch (InterruptedException e) {
                }
            } else if (this.localPhase == phase) {
                //handle connection/reconnection
                onConnectionCall();
            } else {
                //another thread started
                return;
            }

        } while (loop);
    }

    /*
     * this method will be implemented by subclasses. Each subclass
     * will call its onConnection handler
     */
    protected abstract void onConnectionCall();

    /*
     * this method will be implemented by subclasses. Each subclass
     * will init its needed QueueSender/QueueReceiver/TopicPublisher/TopicSubscriber
     */
    protected abstract void connectionCall() throws JMSException, NamingException;
}