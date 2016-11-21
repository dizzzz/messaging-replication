/*
 *  eXist Open Source Native XML Database
 *  Copyright (C) 2012 The eXist Project
 *  http://exist-db.org
 *
 *  This program is free software; you can redistribute it and/or
 *  modify it under the terms of the GNU Lesser General Public License
 *  as published by the Free Software Foundation; either version 2
 *  of the License, or (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU Lesser General Public License for more details.
 *
 *  You should have received a copy of the GNU Lesser General Public
 *  License along with this library; if not, write to the Free Software
 *  Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA  02110-1301  USA
 *
 *  $Id$
 */
package org.exist.jms.replication.publish;

import java.util.List;
import java.util.Map;
import org.apache.commons.lang3.StringUtils;
import org.apache.log4j.Logger;
import org.exist.collections.Collection;
import org.exist.collections.triggers.CollectionTrigger;
import org.exist.collections.triggers.FilteringTrigger;
import org.exist.collections.triggers.TriggerException;
import org.exist.dom.DocumentImpl;
import org.exist.jms.shared.eXistMessage;
import org.exist.jms.replication.shared.MessageHelper;
import org.exist.jms.replication.shared.TransportException;
import org.exist.storage.DBBroker;
import org.exist.storage.txn.Txn;
import org.exist.xmldb.XmldbURI;

/**
 * Trigger for detecting document and collection changes to have the changes
 * propagated to remote eXist-db instances.
 *
 * @author Dannes Wessels (dannes@exist-db.org)
 */
public class ReplicationTrigger extends FilteringTrigger implements CollectionTrigger {

    private final static Logger LOGGER = Logger.getLogger(ReplicationTrigger.class);
    
    private static final String BLOCKED_MESSAGE = "Blocked replication trigger for %s: was received by replication extension.";
    public static final String JMS_EXTENSION_PKG = "org.exist.jms";
    
    private Map<String, List<?>> parameters;

    private boolean isOriginIdAvailable = false;

    /**
     * Constructor.
     *
     * Verifies if new-enough version of eXist-db is used.
     *
     */
    public ReplicationTrigger() {

        super();

        try {
            // Verify if method does exist
            Class.forName("org.exist.Transaction");

            // Yes :-)
            isOriginIdAvailable = true;

        } catch (java.lang.ClassNotFoundException error) {

            // Running an old version of eXist-db
            LOGGER.info("Method Txn.getOriginId() is not available. Please upgrade to eXist-db 2.2 or newer. " + error.getMessage());
        }

    }

    /**
     * Verify if the transaction is started by the JMX extension
     *
     * @param transaction The original transaction
     *
     * @return TRUE when started from JMS else FALSE.
     */
    private boolean isJMSOrigin(Txn transaction) {

        // only try to get OriginId when metjod is available.
        String originId = isOriginIdAvailable ? transaction.getOriginId() : null;

        return StringUtils.startsWith(originId, JMS_EXTENSION_PKG);
    }

    //
    // Document Triggers
    //
    private void afterUpdateCreateDocument(DBBroker broker, Txn transaction, DocumentImpl document, 
                                           eXistMessage.ResourceOperation operation) /* throws TriggerException */ {

        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug(document.getURI().toString());
        }
        
        /** TODO: make optional? (for lJO) */
        if (isJMSOrigin(transaction)) {
            LOGGER.info(String.format(BLOCKED_MESSAGE, document.getURI().toString()));
            return;
        }

        // Create Message
        eXistMessage msg = new eXistMessage();
        msg.setResourceType(eXistMessage.ResourceType.DOCUMENT);
        msg.setResourceOperation(operation);
        msg.setResourcePath(document.getURI().toString());

        // Retrieve Metadata
        Map<String, Object> md = msg.getMetadata();
        MessageHelper.retrieveDocMetadata(md, document.getMetadata());
        MessageHelper.retrieveFromDocument(md, document);
        MessageHelper.retrievePermission(md, document.getPermissions());

        
        // The content is always gzip-ped
        md.put(MessageHelper.EXIST_MESSAGE_CONTENTENCODING, "gzip");

        // Serialize document
        try {
            msg.setPayload(MessageHelper.gzipSerialize(broker, document));

        } catch (Throwable ex) {
            LOGGER.error(String.format("Problem while serializing document (contentLength=%s) to compressed message:%s",                                    
                    document.getContentLength(), ex.getMessage()), ex);
            //throw new TriggerException("Unable to retrieve message payload: " + ex.getMessage());
        }

        // Send Message   
        sendMessage(msg);
    }
    
    @Override
    public void afterCreateDocument(DBBroker broker, Txn transaction,  DocumentImpl document) throws TriggerException {

        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug(document.getURI().toString());
        }
        
        if (isJMSOrigin(transaction)) {
            LOGGER.info(String.format(BLOCKED_MESSAGE, document.getURI().toString()));
            return;
        }

        this.afterUpdateCreateDocument(broker, transaction, document, eXistMessage.ResourceOperation.CREATE);
    }

    @Override
    public void afterUpdateDocument(DBBroker broker, Txn transaction, DocumentImpl document) throws TriggerException {

        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug(document.getURI().toString());
        }
        
        if (isJMSOrigin(transaction)) {
            LOGGER.info(String.format(BLOCKED_MESSAGE, document.getURI().toString()));
            return;
        }

        this.afterUpdateCreateDocument(broker, transaction, document, eXistMessage.ResourceOperation.UPDATE);
    }

    @Override
    public void afterCopyDocument(DBBroker broker, Txn transaction, DocumentImpl document, XmldbURI oldUri) throws TriggerException {

        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug(String.format("%s %s", document.getURI().toString(), oldUri.toString()));
        }
        
        if (isJMSOrigin(transaction)) {
            LOGGER.info(String.format(BLOCKED_MESSAGE, document.getURI().toString()));
            return;
        }

        // Create Message
        eXistMessage msg = new eXistMessage();
        msg.setResourceType(eXistMessage.ResourceType.DOCUMENT);
        msg.setResourceOperation(eXistMessage.ResourceOperation.COPY);
        msg.setResourcePath(oldUri.toString());
        msg.setDestinationPath(document.getURI().toString());

        // Send Message   
        sendMessage(msg);
    }

    @Override
    public void afterMoveDocument(DBBroker broker, Txn transaction, DocumentImpl document, XmldbURI oldUri) throws TriggerException {

        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug(String.format("%s %s", document.getURI().toString(), oldUri.toString()));
        }
        
        if (isJMSOrigin(transaction)) {
            LOGGER.info(String.format(BLOCKED_MESSAGE, document.getURI().toString()));
            return;
        }

        // Create Message
        eXistMessage msg = new eXistMessage();
        msg.setResourceType(eXistMessage.ResourceType.DOCUMENT);
        msg.setResourceOperation(eXistMessage.ResourceOperation.MOVE);
        msg.setResourcePath(oldUri.toString());
        msg.setDestinationPath(document.getURI().toString());

        // Send Message   
        sendMessage(msg);
    }

    @Override
    public void afterDeleteDocument(DBBroker broker, Txn transaction, XmldbURI uri) throws TriggerException {

        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug(uri.toString());
        }
        
        if (isJMSOrigin(transaction)) {
            LOGGER.info(String.format(BLOCKED_MESSAGE, uri.toString()));
            return;
        }

        // Create Message
        eXistMessage msg = new eXistMessage();
        msg.setResourceType(eXistMessage.ResourceType.DOCUMENT);
        msg.setResourceOperation(eXistMessage.ResourceOperation.DELETE);
        msg.setResourcePath(uri.toString());

        // Send Message   
        sendMessage(msg);
    }

    //
    // Collection Triggers
    //
    @Override
    public void afterCreateCollection(DBBroker broker, Txn transaction, Collection collection) throws TriggerException {
        
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug(collection.getURI().toString());
        }
        
        if (isJMSOrigin(transaction)) {
            LOGGER.info(String.format(BLOCKED_MESSAGE, collection.getURI().toString()));
        }

        // Create Message
        eXistMessage msg = new eXistMessage();
        msg.setResourceType(eXistMessage.ResourceType.COLLECTION);
        msg.setResourceOperation(eXistMessage.ResourceOperation.CREATE);
        msg.setResourcePath(collection.getURI().toString());

        Map<String, Object> md = msg.getMetadata();
        MessageHelper.retrievePermission(md, collection.getPermissions());

        // Send Message   
        sendMessage(msg);
    }

    @Override
    public void afterCopyCollection(DBBroker broker, Txn transaction, Collection collection, XmldbURI oldUri) throws TriggerException {
        
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug(String.format("%s %s", collection.getURI().toString(), oldUri.toString()));
        }
        
        if (isJMSOrigin(transaction)) {
            LOGGER.info(String.format(BLOCKED_MESSAGE, collection.getURI().toString()));
        }

        // Create Message
        eXistMessage msg = new eXistMessage();
        msg.setResourceType(eXistMessage.ResourceType.COLLECTION);
        msg.setResourceOperation(eXistMessage.ResourceOperation.COPY);
        msg.setResourcePath(oldUri.toString());
        msg.setDestinationPath(collection.getURI().toString());

        // Send Message   
        sendMessage(msg);
    }

    @Override
    public void afterMoveCollection(DBBroker broker, Txn transaction, Collection collection, XmldbURI oldUri) throws TriggerException {
        
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug(String.format("%s %s", collection.getURI().toString(), oldUri.toString()));
        }
        
        if (isJMSOrigin(transaction)) {
            LOGGER.info(String.format(BLOCKED_MESSAGE, collection.getURI().toString()));
            return;
        }

        // Create Message
        eXistMessage msg = new eXistMessage();
        msg.setResourceType(eXistMessage.ResourceType.COLLECTION);
        msg.setResourceOperation(eXistMessage.ResourceOperation.MOVE);
        msg.setResourcePath(oldUri.toString());
        msg.setDestinationPath(collection.getURI().toString());

        // Send Message   
        sendMessage(msg);
    }

    @Override
    public void afterDeleteCollection(DBBroker broker, Txn transaction, XmldbURI uri) throws TriggerException {
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug(uri.toString());
        }
        
        if (isJMSOrigin(transaction)) {
            LOGGER.info(String.format(BLOCKED_MESSAGE, uri.toString()));
            return;
        }

        // Create Message
        eXistMessage msg = new eXistMessage();
        msg.setResourceType(eXistMessage.ResourceType.COLLECTION);
        msg.setResourceOperation(eXistMessage.ResourceOperation.DELETE);
        msg.setResourcePath(uri.toString());

        // Send Message   
        sendMessage(msg);
    }
    
    // 
    // Metadata triggers
    //    

    @Override
    public void afterUpdateDocumentMetadata(DBBroker broker, Txn transaction, DocumentImpl document) throws TriggerException {
        
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug(document.getURI().toString());
        }

        /*
         * If the action is originated from a trigger, do not process it again
         */
        if (isJMSOrigin(transaction)) {
            LOGGER.info(String.format(BLOCKED_MESSAGE, document.getURI().toString()));
            return;
        }

        // Create Message
        eXistMessage msg = new eXistMessage();
        msg.setResourceType(eXistMessage.ResourceType.DOCUMENT);
        msg.setResourceOperation(eXistMessage.ResourceOperation.METADATA);
        msg.setResourcePath(document.getURI().toString());

        // Retrieve Metadata
        Map<String, Object> md = msg.getMetadata();
        MessageHelper.retrieveDocMetadata(md, document.getMetadata());
        MessageHelper.retrieveFromDocument(md, document);
        MessageHelper.retrievePermission(md, document.getPermissions());

        // Send Message   
        sendMessage(msg);
    }
    
    //
    // Misc         
    //
    @Override
    public void configure(DBBroker broker, Collection parentCollection, Map<String, List<?>> parameters) throws TriggerException {
        super.configure(broker, parentCollection, parameters);

        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug(String.format("Configuring replication trigger for collection '%s'", parentCollection.getURI()));
        }

        this.parameters = parameters;

    }

    /**
     * Send 'trigger' message with parameters set using
     * {@link #configure(org.exist.storage.DBBroker, org.exist.collections.Collection, java.util.Map)}
     */
    private void sendMessage(eXistMessage msg) /* throws TriggerException  */ {
        // Send Message   
        JMSMessageSender sender = new JMSMessageSender(parameters);
        try {
            sender.sendMessage(msg);

        } catch (TransportException ex) {
            LOGGER.error(ex.getMessage(), ex);
            //throw new TriggerException(ex.getMessage(), ex);
            
        } catch (Throwable ex) {
            LOGGER.error(ex.getMessage(), ex);
            //throw new TriggerException(ex.getMessage(), ex);
        }
    }

    /*
     * ****** unused methods follow ******
     */
    //@Override
    @Deprecated
    public void prepare(int event, DBBroker broker, Txn transaction,
            XmldbURI documentPath, DocumentImpl existingDocument) throws TriggerException {
        // Ignored
    }

    //@Override
    @Deprecated
    public void finish(int event, DBBroker broker, Txn transaction,
            XmldbURI documentPath, DocumentImpl document) {
        // Ignored
    }

    @Override
    public void beforeCreateDocument(DBBroker broker, Txn transaction,
            XmldbURI uri) throws TriggerException {
        // Ignored
    }

    @Override
    public void beforeUpdateDocument(DBBroker broker, Txn transaction,
            DocumentImpl document) throws TriggerException {
        // Ignored
    }

    @Override
    public void beforeCopyDocument(DBBroker broker, Txn transaction,
            DocumentImpl document, XmldbURI newUri) throws TriggerException {
        // Ignored
    }

    @Override
    public void beforeMoveDocument(DBBroker broker, Txn transaction,
            DocumentImpl document, XmldbURI newUri) throws TriggerException {
        // Ignored
    }

    @Override
    public void beforeDeleteDocument(DBBroker broker, Txn transaction,
            DocumentImpl document) throws TriggerException {
        // Ignored
    }
    
    @Override
    public void beforeUpdateDocumentMetadata(DBBroker broker, Txn txn, DocumentImpl document) throws TriggerException {
        // Ignored
    }

    //@Override
    @Deprecated
    public void prepare(int event, DBBroker broker, Txn transaction, Collection collection,
            Collection newCollection) throws TriggerException {
        // Ignored
    }

    //@Override
    @Deprecated
    public void finish(int event, DBBroker broker, Txn transaction, Collection collection,
            Collection newCollection) {
        // Ignored
    }

    @Override
    public void beforeCreateCollection(DBBroker broker, Txn transaction,
            XmldbURI uri) throws TriggerException {
        // Ignored
    }

    @Override
    public void beforeCopyCollection(DBBroker broker, Txn transaction, Collection collection,
            XmldbURI newUri) throws TriggerException {
        // Ignored
    }

    @Override
    public void beforeMoveCollection(DBBroker broker, Txn transaction, Collection collection,
            XmldbURI newUri) throws TriggerException {
        // Ignored
    }

    @Override
    public void beforeDeleteCollection(DBBroker broker, Txn transaction,
            Collection collection) throws TriggerException {
        // Ignored
    }
    
    

}
