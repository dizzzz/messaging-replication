package org.exist.messaging.xquery;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Enumeration;
import java.util.Properties;
import javax.jms.Connection;
import javax.jms.ConnectionFactory;
import javax.jms.Destination;
import javax.jms.JMSException;
import javax.jms.Message;
import javax.jms.MessageConsumer;
import javax.jms.MessageListener;
import javax.jms.ObjectMessage;
import javax.jms.Session;
import javax.jms.TextMessage;
import javax.naming.Context;
import javax.naming.InitialContext;
import org.apache.log4j.Logger;
import org.exist.messaging.configuration.JmsConfiguration;
import org.exist.xquery.XPathException;
import org.exist.xquery.XQueryContext;
import org.exist.xquery.functions.map.MapType;
import org.exist.xquery.value.AtomicValue;
import org.exist.xquery.value.BooleanValue;
import org.exist.xquery.value.DecimalValue;
import org.exist.xquery.value.DoubleValue;
import org.exist.xquery.value.FloatValue;
import org.exist.xquery.value.FunctionReference;
import org.exist.xquery.value.IntegerValue;
import org.exist.xquery.value.Item;
import org.exist.xquery.value.Sequence;
import org.exist.xquery.value.StringValue;
import org.exist.xquery.value.ValueSequence;

/**
 *
 * @author wessels
 */
public class Receiver {

    private final static Logger LOG = Logger.getLogger(Receiver.class);
    // Setup listener
    MyJMSListener myListener = new MyJMSListener();
    private FunctionReference ref;
    private JmsConfiguration config;
    private XQueryContext context;

    Receiver(FunctionReference ref, JmsConfiguration config, XQueryContext context) {
        this.ref = ref;
        this.config = config;
        this.context = context;
    }

    void start() {

        try {
            myListener.setFunctionReference(ref);
            myListener.setXQueryContext(context);

            // Setup Context
            Properties props = new Properties();
            props.setProperty(Context.INITIAL_CONTEXT_FACTORY, config.getInitialContextFactory());
            props.setProperty(Context.PROVIDER_URL, config.getProviderURL());
            Context context = new InitialContext(props);

            // Setup connection
            ConnectionFactory connectionFactory = (ConnectionFactory) context.lookup(config.getConnectionFactory());
            Connection connection = connectionFactory.createConnection();

            // Setup session
            Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);

            // Setup destination
            Destination destination = (Destination) context.lookup(config.getDestination());
            LOG.info("Destination=" + destination);

            // Setup consumer
            MessageConsumer messageConsumer = session.createConsumer(destination);
            messageConsumer.setMessageListener(myListener);

            // Start listener
            connection.start();

            LOG.info("Receiver is ready");

        } catch (Throwable t) {
            LOG.error(t.getMessage(), t);
        }

    }

    private static class MyJMSListener implements MessageListener {

        private FunctionReference functionReference;
        private XQueryContext xqueryContext;

//        public MyJMSListener() {
//        }
        @Override
        public void onMessage(Message msg) {
            try {
                LOG.info(String.format("msgId='%s'", msg.getJMSMessageID()));

                // Show content
                AtomicValue content = null;
                
                if (msg instanceof TextMessage) {
                    LOG.info("TextMessage");
                    content = new StringValue(((TextMessage) msg).getText());

                } else if (msg instanceof ObjectMessage) {
                    Object obj = ((ObjectMessage) msg).getObject();
                    LOG.info(obj.getClass().getCanonicalName());

                    if (obj instanceof BigInteger) {
                        content = new IntegerValue((BigInteger) obj);

                    } else if (obj instanceof Double) {
                        content = new DoubleValue((Double) obj);

                    } else if (obj instanceof BigDecimal) {
                        content = new DecimalValue((BigDecimal) obj);

                    } else if (obj instanceof Boolean) {
                        content = new BooleanValue((Boolean) obj);

                    } else if (obj instanceof Float) {
                        content = new FloatValue((Float) obj);

                    } else {
                        LOG.error(String.format("Unable to convert %s", obj.toString()));
                    }

                } else {
                    LOG.info(msg.getClass().getCanonicalName());
                    //content = msg.toString();
                }
                LOG.info(String.format("content='%s' type='%s'", content, msg.getJMSType()));
                
                // Copy property values into Maptype
                MapType msgProperties = getMessageProperties(msg, xqueryContext);
                MapType jmsProperties = getJmsProperties(msg, xqueryContext);
                
                // Setup parameters callback function
                Sequence params[] = new Sequence[3];
                params[0] = content;
                params[1] = msgProperties;
                params[2] = jmsProperties;
                
                // Execute callback function
                functionReference.evalFunction(null, null, params);

            } catch (JMSException ex) {
                LOG.error(ex);
                ex.printStackTrace();

            } catch (XPathException ex) {
                LOG.error(ex);
                ex.printStackTrace();

            } catch (Throwable ex) {
                LOG.error(ex);
                ex.printStackTrace();
            }

        }

        public void setFunctionReference(FunctionReference ref) {
            this.functionReference = ref;
        }
        
        public void setXQueryContext(XQueryContext context) {
            this.xqueryContext = context;
        }
        
        private MapType getMessageProperties(Message msg, XQueryContext xqueryContext) throws XPathException, JMSException {
            // Copy property values into Maptype
            MapType map = new MapType(xqueryContext);

            Enumeration props = msg.getPropertyNames();
            while (props.hasMoreElements()) {
                String elt = (String) props.nextElement();
                String value = msg.getStringProperty(elt);
                add(map, elt, value);
            }
            return map;
        }
        
        private MapType getJmsProperties(Message msg, XQueryContext xqueryContext) throws XPathException, JMSException {
            // Copy property values into Maptype
            MapType map = new MapType(xqueryContext);

            add(map, "JMSMessageID", msg.getJMSMessageID());
            add(map, "JMSCorrelationID", msg.getJMSCorrelationID());
            add(map, "JMSType", msg.getJMSType());
            add(map, "JMSPriority", ""+msg.getJMSPriority());
            add(map, "JMSExpiration", ""+msg.getJMSExpiration());
            add(map, "JMSTimestamp", ""+msg.getJMSTimestamp());
            
            return map;
        }
        
        private void add(MapType map, String key, String value) throws XPathException {
            if (map != null && key != null && !key.isEmpty() && value != null) {
                map.add(new StringValue(key), new ValueSequence(new StringValue(value)));
            }
        }
        
    }
}
