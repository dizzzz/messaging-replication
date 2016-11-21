/*
 *  eXist Open Source Native XML Database
 *  Copyright (C) 2010 The eXist Project
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
package org.exist.jms.replication.subscribe;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Enumeration;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import javax.jms.BytesMessage;
import javax.jms.JMSException;
import javax.jms.Message;
import org.apache.commons.lang3.StringUtils;
import org.apache.log4j.Logger;
import org.exist.collections.Collection;
import org.exist.collections.IndexInfo;
import org.exist.dom.DocumentImpl;
import org.exist.jms.replication.shared.MessageHelper;
import org.exist.jms.shared.Constants;
import org.exist.jms.shared.Identity;
import org.exist.jms.shared.Report;
import org.exist.jms.shared.eXistMessage;
import org.exist.jms.shared.eXistMessagingListener;
import org.exist.security.Account;
import org.exist.security.Group;
import org.exist.security.Permission;
import org.exist.storage.BrokerPool;
import org.exist.storage.DBBroker;
import org.exist.storage.lock.Lock;
import org.exist.storage.txn.TransactionManager;
import org.exist.storage.txn.Txn;
import org.exist.util.MimeTable;
import org.exist.util.MimeType;
import org.exist.util.VirtualTempFile;
import org.exist.util.VirtualTempFileInputSource;
import org.exist.xmldb.XmldbURI;
import org.xml.sax.InputSource;

/**
 * JMS listener for receiving JMS replication messages
 *
 * @author Dannes Wessels
 */
public class ReplicationJmsListener extends eXistMessagingListener {

    private final static Logger LOG = Logger.getLogger(ReplicationJmsListener.class);
    private BrokerPool brokerPool = null;
    private org.exist.security.SecurityManager securityManager = null;

    private String localID = null;
    private Report report = null;

    /**
     * Constructor
     *
     * @param brokerpool Reference to database broker pool
     */
    public ReplicationJmsListener(BrokerPool brokerpool) {
        brokerPool = brokerpool;
        securityManager = brokerpool.getSecurityManager();
        localID = Identity.getInstance().getIdentity();
        report = getReport();
    }

    /**
     * Set origin of transaction
     *
     * @param transaction The eXist-db transaction
     */
    private void setOrigin(Txn transaction) {
        try {
            transaction.setOriginId(this.getClass().getName());

        } catch (java.lang.NoSuchMethodError error) {
            // Running an old version of eXist-db
            LOG.error("Method Txn.getOriginId() is not available. Please upgrade to eXist-db 2.2 or newer. " + error.getMessage());
        }
    }

    @Override
    public void onMessage(Message msg) {

        // Start reporting
        report.start();

        try {
            // Detect if the sender of the incoming message is the receiver
            if (StringUtils.isNotEmpty(localID)) {
                String remoteID = msg.getStringProperty(Constants.EXIST_INSTANCE_ID);
                if (localID.equals(remoteID)) {
                    LOG.info("Incoming JMS messsage was originally sent by this instance. Stopped processing.");

                    // We need to ack the message
                    msg.acknowledge();

                    return;
                }
            }

            if (msg instanceof BytesMessage) {

                // Prepare received message
                eXistMessage em = convertMessage((BytesMessage) msg);

                Enumeration e = msg.getPropertyNames();
                while (e.hasMoreElements()) {
                    Object next = e.nextElement();
                    if (next instanceof String) {
                        em.getMetadata().put((String) next, msg.getObjectProperty((String) next));
                    }
                }

                // Report some details into logging
                if (LOG.isDebugEnabled()) {
                    LOG.debug(em.getReport());
                }

                // First step: distinct between update for documents and messsages
                switch (em.getResourceType()) {
                    case DOCUMENT:
                        handleDocument(em);
                        break;
                    case COLLECTION:
                        handleCollection(em);
                        break;
                    default:
                        String errorMessage = String.format("Unknown resource type %s", em.getResourceType());
                        LOG.error(errorMessage);
                        throw new MessageReceiveException(errorMessage);
                }
                report.incMessageCounterOK();

            } else {
                // Only ByteMessage objects supported. 
                throw new MessageReceiveException(String.format("Could not handle message type %s", msg.getClass().getSimpleName()));
            }
            
            // We need to ack the message
            msg.acknowledge();

        } catch (MessageReceiveException ex) {
            // Thrown by local code. Just make it pass\
            report.addListenerError(ex);
            LOG.error(String.format("Could not handle received message: %s", ex.getMessage()), ex);
            throw ex;

        } catch (Throwable t) {
            // Something really unexpected happened. Report
            report.addListenerError(t);
            LOG.error(t.getMessage(), t);
            throw new MessageReceiveException(String.format("Could not handle received message: %s", t.getMessage()), t);

        } finally {
            // update statistics
            report.stop();
            report.incMessageCounterTotal();
            report.addCumulatedProcessingTime();
        }
    }

    //
    // The code below handles the incoming message ; DW: should be moved to seperate class
    //
    /**
     * Convert JMS ByteMessage into an eXist-db specific message.
     *
     * @param bm The original message
     * @return The converted message
     */
    private eXistMessage convertMessage(BytesMessage bm) {
        eXistMessage em = new eXistMessage();

        try {
            String value = bm.getStringProperty(eXistMessage.EXIST_RESOURCE_TYPE);
            em.setResourceType(value);

            value = bm.getStringProperty(eXistMessage.EXIST_RESOURCE_OPERATION);
            em.setResourceOperation(value);

            value = bm.getStringProperty(eXistMessage.EXIST_SOURCE_PATH);
            em.setResourcePath(value);

            value = bm.getStringProperty(eXistMessage.EXIST_DESTINATION_PATH);
            em.setDestinationPath(value);

            // This is potentially memory intensive
            long size = bm.getBodyLength();
            byte[] payload = new byte[(int) size];
            bm.readBytes(payload);
            em.setPayload(payload);

        } catch (JMSException ex) {
            String errorMessage = String.format("Unable to convert incoming message. (%s):  %s", ex.getErrorCode(), ex.getMessage());
            LOG.error(errorMessage, ex);
            throw new MessageReceiveException(errorMessage);

        } catch (IllegalArgumentException ex) {
            String errorMessage = String.format("Unable to convert incoming message. %s", ex.getMessage());
            LOG.error(errorMessage, ex);
            throw new MessageReceiveException(errorMessage);
        }

        return em;
    }

    /**
     * Handle operation on documents
     *
     * @param em Message containing information about documents
     */
    private void handleDocument(eXistMessage em) {

        switch (em.getResourceOperation()) {
            case CREATE:
            case UPDATE:
                createUpdateDocument(em);
                break;

            case METADATA:
                updateMetadataDocument(em);
                break;

            case DELETE:
                deleteDocument(em);
                break;

            case MOVE:
                relocateDocument(em, false);
                break;

            case COPY:
                relocateDocument(em, true);
                break;

            default:
                String errorMessage = String.format("Unknown resource type %s", em.getResourceOperation());
                LOG.error(errorMessage);
                throw new MessageReceiveException(errorMessage);
        }
    }

    /**
     * Handle operation on collections
     *
     * @param em Message containing information about collections
     */
    private void handleCollection(eXistMessage em) {

        switch (em.getResourceOperation()) {
            case CREATE:
            case UPDATE:
                createCollection(em);
                break;

            case METADATA:
                updateMetadataCollection(em);
                break;

            case DELETE:
                deleteCollection(em);
                break;

            case MOVE:
                relocateCollection(em, false);
                break;

            case COPY:
                relocateCollection(em, true);
                break;

            default:
                String errorMessage = "Unknown change type";
                LOG.error(errorMessage);
                throw new MessageReceiveException(errorMessage);
        }
    }

    /**
     * Created document in database
     */
    private void createUpdateDocument(eXistMessage em) {

        Map<String, Object> metaData = em.getMetadata();

        XmldbURI sourcePath = XmldbURI.create(em.getResourcePath());
        XmldbURI colURI = sourcePath.removeLastSegment();
        XmldbURI docURI = sourcePath.lastSegment();

        // References to the database
        DBBroker broker = null;
        Collection collection = null;

        // Get mime, or NULL when not available
        MimeType mime = MimeTable.getInstance().getContentTypeFor(docURI.toString());
        if (mime == null) {
            mime = MimeType.BINARY_TYPE;
        }

        // Get OWNER and Group
        String userName = getUserName(metaData);
        String groupName = getGroupName(metaData);

        // Get MIME_TYPE
        String mimeType = getMimeType(metaData, sourcePath);

        // Get MODE
        Integer mode = getMode(metaData);

        try {
            broker = brokerPool.get(securityManager.getSystemSubject());

            collection = broker.openCollection(colURI, Lock.WRITE_LOCK);
            if (collection == null) {
                String errorMessage = String.format("Collection %s does not exist", colURI);
                LOG.error(errorMessage);

                // Create collection anyway
                collection = createCollection(colURI, userName, groupName, Permission.DEFAULT_COLLECTION_PERM);
            }

        } catch (Throwable t) {
            if (LOG.isDebugEnabled()) {
                LOG.error(t.getMessage(), t);
            } else {
                LOG.error(t.getMessage());
            }
            throw new MessageReceiveException(String.format("Unable to create collection in database: %s", t.getMessage()));
        }

        // Start transaction
        TransactionManager txnManager = brokerPool.getTransactionManager();
        Txn txn = txnManager.beginTransaction();
        setOrigin(txn);

        try {
            DocumentImpl doc;
            if (mime.isXMLType()) {

                // Stream into database
                VirtualTempFile vtf = new VirtualTempFile(em.getPayload());
                VirtualTempFileInputSource vt = new VirtualTempFileInputSource(vtf);
                InputStream byteInputStream = vt.getByteStream();

                // DW: future improvement: determine compression based on property.
                GZIPInputStream gis = new GZIPInputStream(byteInputStream);
                InputSource inputsource = new InputSource(gis);

                // DW: collection can be null?
                IndexInfo info = collection.validateXMLResource(txn, broker, docURI, inputsource);
                doc = info.getDocument();
                doc.getMetadata().setMimeType(mimeType);

                // reconstruct gzip input stream
                byteInputStream.reset();
                gis = new GZIPInputStream(byteInputStream);
                inputsource = new InputSource(gis);

                collection.store(txn, broker, info, inputsource, false);
                inputsource.getByteStream().close();

            } else {

                // Stream into database
                byte[] payload = em.getPayload();
                ByteArrayInputStream bais = new ByteArrayInputStream(payload);
                GZIPInputStream gis = new GZIPInputStream(bais);

                try (BufferedInputStream bis = new BufferedInputStream(gis)) {
                    // DW: collection can be null
                    doc = collection.addBinaryResource(txn, broker, docURI, bis, mimeType, payload.length);
                }
            }

            // Set owner,group and permissions
            Permission permission = doc.getPermissions();
            if (userName != null) {
                permission.setOwner(userName);
            }
            if (groupName != null) {
                permission.setGroup(groupName);
            }
            if (mode != null) {
                permission.setMode(mode);
            }

            // Commit change
            txnManager.commit(txn);

        } catch (Throwable ex) {

            if (LOG.isDebugEnabled()) {
                LOG.error(ex.getMessage(), ex);
            } else {
                LOG.error(ex.getMessage());
            }

            txnManager.abort(txn);
            throw new MessageReceiveException(String.format("Unable to write document into database: %s", ex.getMessage()));

        } finally {

            if (collection != null) {
                collection.release(Lock.WRITE_LOCK);
            }

            txnManager.close(txn);
            brokerPool.release(broker);

        }
    }

    /**
     * Metadata is updated in database
     *
     * TODO not usable yet
     */
    private void updateMetadataDocument(eXistMessage em) {
        // Permissions
        // Mimetype
        // owner/groupname

        XmldbURI sourcePath = XmldbURI.create(em.getResourcePath());
        XmldbURI colURI = sourcePath.removeLastSegment();
        XmldbURI docURI = sourcePath.lastSegment();

        // References to the database
        DBBroker broker = null;
        Collection collection = null;
        DocumentImpl resource;

        TransactionManager txnManager = brokerPool.getTransactionManager();
        Txn txn = txnManager.beginTransaction();
        setOrigin(txn);

        try {
            broker = brokerPool.get(securityManager.getSystemSubject());

            // Open collection if possible, else abort
            collection = broker.openCollection(colURI, Lock.WRITE_LOCK);
            if (collection == null) {
                String errorText = String.format("Collection does not exist %s", colURI);
                LOG.error(errorText);
                txnManager.abort(txn);

                // be silent
                return;
            }

            // Open document if possible, else abort
            resource = collection.getDocument(broker, docURI);
            if (resource == null) {
                String errorText = String.format("No resource found for path: %s", sourcePath);
                LOG.error(errorText);
                txnManager.abort(txn);

                // be silent
                return;
            }

            // Get supplied metadata
            Map<String, Object> metaData = em.getMetadata();

            Permission perms = resource.getPermissions();

            String userName = getUserName(metaData);
            if (userName != null) {
                perms.setGroup(userName);
            }

            String groupName = getGroupName(metaData);
            if (groupName != null) {
                perms.setGroup(groupName);
            }

            Integer mode = getMode(metaData);
            if (mode != null) {
                perms.setMode(mode);
            }

            String mimeType = getMimeType(metaData, sourcePath);
            if (mimeType != null) {
                resource.getMetadata().setMimeType(mimeType);
            }

            // Commit change
            txnManager.commit(txn);

        } catch (Throwable e) {
            LOG.error(e);
            txnManager.abort(txn);
            throw new MessageReceiveException(e.getMessage(), e);

        } finally {

            // TODO: check if can be done earlier
            if (collection != null) {
                collection.release(Lock.WRITE_LOCK);
            }
            txnManager.close(txn);
            brokerPool.release(broker);

        }

    }

    /**
     * Remove document from database
     */
    private void deleteDocument(eXistMessage em) {

        XmldbURI sourcePath = XmldbURI.create(em.getResourcePath());
        XmldbURI colURI = sourcePath.removeLastSegment();
        XmldbURI docURI = sourcePath.lastSegment();

        // References to the database
        DBBroker broker = null;
        Collection collection = null;
        DocumentImpl resource;

        TransactionManager txnManager = brokerPool.getTransactionManager();
        Txn txn = txnManager.beginTransaction();
        setOrigin(txn);

        try {
            broker = brokerPool.get(securityManager.getSystemSubject());

            // Open collection if possible, else abort
            collection = broker.openCollection(colURI, Lock.WRITE_LOCK);
            if (collection == null) {
                String errorText = String.format("Collection does not exist %s", colURI);
                LOG.error(errorText);
                txnManager.abort(txn);

                // silently ignore
                return;
            }

            // Open document if possible, else abort
            resource = collection.getDocument(broker, docURI);
            if (resource == null) {
                String errorText = String.format("No resource found for path: %s", sourcePath);
                LOG.error(errorText);
                txnManager.abort(txn);

                // silently ignore
                return;
            }

            // This delete is based on mime-type /ljo 
            if (resource.getResourceType() == DocumentImpl.BINARY_FILE) {
                collection.removeBinaryResource(txn, broker, resource.getFileURI());

            } else {
                collection.removeXMLResource(txn, broker, resource.getFileURI());
            }

            // Commit change
            txnManager.commit(txn);

        } catch (Throwable t) {

            if (LOG.isDebugEnabled()) {
                LOG.error(t.getMessage(), t);
            } else {
                LOG.error(t.getMessage());
            }

            txnManager.abort(txn);
            throw new MessageReceiveException(t.getMessage(), t);

        } finally {

            if (collection != null) {
                collection.release(Lock.WRITE_LOCK);
            }

            txnManager.close(txn);
            brokerPool.release(broker);

        }
    }

    /**
     * Remove collection from database
     */
    private void deleteCollection(eXistMessage em) {

        XmldbURI sourcePath = XmldbURI.create(em.getResourcePath());

        DBBroker broker = null;
        Collection collection = null;

        TransactionManager txnManager = brokerPool.getTransactionManager();
        Txn txn = txnManager.beginTransaction();
        setOrigin(txn);

        try {
            broker = brokerPool.get(securityManager.getSystemSubject());

            // Open collection if possible, else abort
            collection = broker.openCollection(sourcePath, Lock.WRITE_LOCK);
            if (collection == null) {
                txnManager.abort(txn);
                // be silent
                return;
            }

            // Remove collection
            broker.removeCollection(txn, collection);

            // Commit change
            txnManager.commit(txn);

        } catch (Throwable t) {

            if (LOG.isDebugEnabled()) {
                LOG.error(t.getMessage(), t);
            } else {
                LOG.error(t.getMessage());
            }

            txnManager.abort(txn);
            throw new MessageReceiveException(t.getMessage());

        } finally {

            if (collection != null) {
                collection.release(Lock.WRITE_LOCK);
            }

            txnManager.close(txn);
            brokerPool.release(broker);

        }
    }

    /**
     * Created collection in database
     */
    private void createCollection(eXistMessage em) {

        XmldbURI sourcePath = XmldbURI.create(em.getResourcePath());

        Map<String, Object> metaData = em.getMetadata();

        // Get OWNER/GROUP/MODE
        String userName = getUserName(metaData);
        String groupName = getGroupName(metaData);
        Integer mode = getMode(metaData);

        createCollection(sourcePath, userName, groupName, mode);
    }

    private Collection createCollection(XmldbURI sourcePath, String userName, String groupName, Integer mode) throws MessageReceiveException {
        DBBroker broker = null;
        Collection newCollection = null;

        TransactionManager txnManager = brokerPool.getTransactionManager();
        Txn txn = txnManager.beginTransaction();
        setOrigin(txn);

        try {
            broker = brokerPool.get(securityManager.getSystemSubject());

            // Check if collection is already there
            Collection collection = broker.openCollection(sourcePath, Lock.WRITE_LOCK);
            if (collection != null) {
                String errorText = String.format("Collection %s already exists", sourcePath);
                LOG.error(errorText);

                collection.release(Lock.WRITE_LOCK);
                txnManager.abort(txn);

                // Just return the existent collection
                return collection;
            }

            // Create collection
            newCollection = broker.getOrCreateCollection(txn, sourcePath);
            // Set owner,group and permissions
            Permission permission = newCollection.getPermissions();
            if (userName != null) {
                permission.setOwner(userName);
            }
            if (groupName != null) {
                permission.setGroup(groupName);
            }
            if (mode != null) {
                permission.setMode(mode);
            }
            broker.saveCollection(txn, newCollection);
            broker.flush();

            // Commit change
            txnManager.commit(txn);

        } catch (Throwable t) {

            if (LOG.isDebugEnabled()) {
                LOG.error(t.getMessage(), t);
            } else {
                LOG.error(t.getMessage());
            }

            txnManager.abort(txn);
            throw new MessageReceiveException(t.getMessage(), t);

        } finally {
            // TODO: check if can be done earlier
            if (newCollection != null) {
                newCollection.release(Lock.WRITE_LOCK);
            }
            txnManager.close(txn);
            brokerPool.release(broker);
        }
        return newCollection;
    }

    private void relocateDocument(eXistMessage em, boolean keepDocument) {

        XmldbURI sourcePath = XmldbURI.create(em.getResourcePath());
        XmldbURI sourceColURI = sourcePath.removeLastSegment();
        XmldbURI sourceDocURI = sourcePath.lastSegment();

        XmldbURI destPath = XmldbURI.create(em.getDestinationPath());
        XmldbURI destColURI = destPath.removeLastSegment();
        XmldbURI destDocURI = destPath.lastSegment();

        DBBroker broker = null;

        Collection srcCollection = null;
        DocumentImpl srcDocument;

        Collection destCollection = null;

        // Use the correct lock
        int lockTypeOriginal = keepDocument ? Lock.READ_LOCK : Lock.WRITE_LOCK;

        TransactionManager txnManager = brokerPool.getTransactionManager();
        Txn txn = txnManager.beginTransaction();
        setOrigin(txn);

        try {
            broker = brokerPool.get(securityManager.getSystemSubject());

            // Open collection if possible, else abort
            srcCollection = broker.openCollection(sourceColURI, lockTypeOriginal);
            if (srcCollection == null) {
                String errorMessage = String.format("Collection not found: %s", sourceColURI);
                LOG.error(errorMessage);
                txnManager.abort(txn);

                // be silent
                return;
            }

            // Open document if possible, else abort
            srcDocument = srcCollection.getDocument(broker, sourceDocURI);
            if (srcDocument == null) {
                String errorMessage = String.format("No resource found for path: %s", sourcePath);
                LOG.error(errorMessage);
                txnManager.abort(txn);

                // be silent
                return;
            }

            // Open collection if possible, else abort
            destCollection = broker.openCollection(destColURI, Lock.WRITE_LOCK);
            if (destCollection == null) {
                String errorMessage = String.format("Destination collection %s does not exist.", destColURI);
                LOG.error(errorMessage);
                txnManager.abort(txn);

                // be silent
                return;
            }

            // Perform actial move/copy
            if (keepDocument) {
                broker.copyResource(txn, srcDocument, destCollection, destDocURI);

            } else {
                broker.moveResource(txn, srcDocument, destCollection, destDocURI);
            }

            // Commit change
            txnManager.commit(txn);

        } catch (Throwable e) {
            LOG.error(e);
            txnManager.abort(txn);
            throw new MessageReceiveException(e.getMessage(), e);

        } finally {

            if (destCollection != null) {
                destCollection.release(Lock.WRITE_LOCK);
            }

            if (srcCollection != null) {
                srcCollection.release(lockTypeOriginal);
            }

            txnManager.close(txn);
            brokerPool.release(broker);

        }
    }

    private void relocateCollection(eXistMessage em, boolean keepCollection) {

        XmldbURI sourcePath = XmldbURI.create(em.getResourcePath());

        XmldbURI destPath = XmldbURI.create(em.getDestinationPath());
        XmldbURI destColURI = destPath.removeLastSegment();
        XmldbURI destDocURI = destPath.lastSegment();

        DBBroker broker = null;
        Collection srcCollection = null;
        Collection destCollection = null;

        TransactionManager txnManager = brokerPool.getTransactionManager();
        Txn txn = txnManager.beginTransaction();
        setOrigin(txn);

        try {
            broker = brokerPool.get(securityManager.getSystemSubject());

            // Open collection if possible, else abort
            srcCollection = broker.openCollection(sourcePath, Lock.WRITE_LOCK);
            if (srcCollection == null) {
                String errorMessage = String.format("Collection %s does not exist.", sourcePath);
                LOG.error(errorMessage);
                txnManager.abort(txn);

                // be silent
                return;
            }

            // Open collection if possible, else abort
            destCollection = broker.openCollection(destColURI, Lock.WRITE_LOCK);
            if (destCollection == null) {
                String errorMessage = String.format("Destination collection %s does not exist.", destColURI);
                LOG.error(errorMessage);

                txnManager.abort(txn);

                // be silent
                return;
            }

            // Perform actual move/copy
            if (keepCollection) {
                broker.copyCollection(txn, srcCollection, destCollection, destDocURI);

            } else {
                broker.moveCollection(txn, srcCollection, destCollection, destDocURI);
            }

            // Commit change
            txnManager.commit(txn);

        } catch (Throwable e) {
            LOG.error(e);
            txnManager.abort(txn);
            throw new MessageReceiveException(e.getMessage());

        } finally {

            if (destCollection != null) {
                destCollection.release(Lock.WRITE_LOCK);
            }

            if (srcCollection != null) {
                srcCollection.release(Lock.WRITE_LOCK);
            }

            txnManager.close(txn);
            brokerPool.release(broker);

        }
    }

    @Override
    public String getUsageType() {
        return "replication";
    }

    private void updateMetadataCollection(eXistMessage em) {
        XmldbURI sourceColURI = XmldbURI.create(em.getResourcePath());

        Map<String, Object> metaData = em.getMetadata();

        // Get OWNER/GROUP/MODE
        String userName = getUserName(metaData);
        String groupName = getGroupName(metaData);
        Integer mode = getMode(metaData);

        TransactionManager txnManager = brokerPool.getTransactionManager();
        Txn txn = txnManager.beginTransaction();
        setOrigin(txn);

        DBBroker broker = null;
        Collection srcCollection = null;

        try {
            // TODO get user
            broker = brokerPool.get(securityManager.getSystemSubject());

            // Open collection if possible, else abort
            srcCollection = broker.openCollection(sourceColURI, Lock.WRITE_LOCK);
            if (srcCollection == null) {
                String errorMessage = String.format("Collection not found: %s", sourceColURI);
                LOG.error(errorMessage);
                txnManager.abort(txn);

                // be silent
                return;
            }

            Permission permission = srcCollection.getPermissions();
            if (userName != null) {
                permission.setOwner(userName);
            }
            if (groupName != null) {
                permission.setGroup(groupName);
            }
            if (mode != null) {
                permission.setMode(mode);
            }

            // Commit change
            txnManager.commit(txn);

        } catch (Throwable e) {
            LOG.error(e);
            txnManager.abort(txn);
            throw new MessageReceiveException(e.getMessage());

        } finally {

            if (srcCollection != null) {
                srcCollection.release(Lock.WRITE_LOCK);
            }

            txnManager.close(txn);
            brokerPool.release(broker);

        }

    }


    /**
     * Get valid username for database, else system subject if not valid.
     */
    private String getUserName(Map<String, Object> metaData) {

        String userName = null;
        Object prop = metaData.get(MessageHelper.EXIST_RESOURCE_OWNER);
        if (prop != null && prop instanceof String) {
            userName = (String) prop;
        } else {
            LOG.debug("No username provided");
            return userName;
        }

        Account account = securityManager.getAccount(userName);
        if (account == null) {
            String errorText = String.format("Username %s does not exist.", userName);
            LOG.error(errorText);

            account = securityManager.getSystemSubject();
            userName = account.getName();
        }

        return userName;
    }

    /**
     * Get valid groupname for database for database, else system subject if not existent
     */
    private String getGroupName(Map<String, Object> metaData) {

        String groupName = null;
        Object prop = metaData.get(MessageHelper.EXIST_RESOURCE_GROUP);
        if (prop != null && prop instanceof String) {
            groupName = (String) prop;
        } else {
            LOG.debug("No groupname provided");
            return groupName;
        }

        Group group = securityManager.getGroup(groupName);
        if (group == null) {
            String errorText = String.format("Group %s does not exist.", groupName);
            LOG.error(errorText);

            group = securityManager.getSystemSubject().getDefaultGroup();
            groupName = group.getName();
        }

        return groupName;
    }

    private String getMimeType(Map<String, Object> metaData, XmldbURI sourcePath) {
        MimeTable mimeTable = MimeTable.getInstance();
        String mimeType = null;
        Object prop = metaData.get(MessageHelper.EXIST_RESOURCE_MIMETYPE);
        if (prop != null && prop instanceof String) {
            MimeType mT = mimeTable.getContentTypeFor((String) prop);
            if (mT != null) {
                mimeType = mT.getName();
            }
        }

        // Fallback based on filename
        if (mimeType == null) {
            MimeType mT = mimeTable.getContentTypeFor(sourcePath);

            if (mT == null) {
                throw new MessageReceiveException("Unable to determine mimetype");
            }
            mimeType = mT.getName();
        }

        return mimeType;

    }

    private Integer getMode(Map<String, Object> metaData) {
        // Get/Set permissions
        Integer mode = null;
        Object prop = metaData.get(MessageHelper.EXIST_RESOURCE_MODE);
        if (prop != null && prop instanceof Integer) {
            mode = (Integer) prop;
        } else {
            LOG.debug("No mode provided");
            return mode;
        }
        return mode;
    }

   

}
