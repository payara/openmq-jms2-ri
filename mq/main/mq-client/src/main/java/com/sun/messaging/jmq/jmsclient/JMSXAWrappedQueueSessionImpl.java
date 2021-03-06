/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2000-2013 Oracle and/or its affiliates. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License.  You can
 * obtain a copy of the License at
 * https://glassfish.dev.java.net/public/CDDL+GPL_1_1.html
 * or packager/legal/LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at packager/legal/LICENSE.txt.
 *
 * GPL Classpath Exception:
 * Oracle designates this particular file as subject to the "Classpath"
 * exception as provided by Oracle in the GPL Version 2 section of the License
 * file that accompanied this code.
 *
 * Modifications:
 * If applicable, add the following below the License Header, with the fields
 * enclosed by brackets [] replaced by your own identifying information:
 * "Portions Copyright [year] [name of copyright owner]"
 *
 * Contributor(s):
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 */

/*
 * @(#)JMSXAWrappedQueueSessionImpl.java	1.4 06/27/07
 */ 

package com.sun.messaging.jmq.jmsclient;

import java.util.Hashtable;
import javax.jms.*;
import javax.transaction.xa.Xid;
import javax.transaction.xa.XAResource;

import com.sun.jms.spi.xa.*;

/**
 * An XAQueueSession provides a regular QueueSession which can be used to
 * create QueueReceivers, QueueSenders and QueueBrowsers (optional).
 *
 * <P>XASession extends the capability of Session by adding access to a JMS
 * provider's support for JTA (optional). This support takes the form of a
 * <CODE>javax.transaction.xa.XAResource</CODE> object. The functionality of
 * this object closely resembles that defined by the standard X/Open XA
 * Resource interface.
 *
 * <P>An application server controls the transactional assignment of an
 * XASession by obtaining its XAResource. It uses the XAResource to assign
 * the session to a transaction; prepare and commit work on the
 * transaction; etc.
 *
 * <P>An XAResource provides some fairly sophisticated facilities for
 * interleaving work on multiple transactions; recovering a list of
 * transactions in progress; etc. A JTA aware JMS provider must fully
 * implement this functionality. This could be done by using the services
 * of a database that supports XA or a JMS provider may choose to implement
 * this functionality from scratch.
 *
 * <P>A client of the application server is given what it thinks is a
 * regular JMS Session. Behind the scenes, the application server controls
 * the transaction management of the underlying XASession.
 *
 * @see         javax.jms.XASession javax.jms.XASession
 * @see         javax.jms.XAQueueSession javax.jms.XAQueueSession
 */

public class JMSXAWrappedQueueSessionImpl implements JMSXAQueueSession, 
                                          JMSXAWrappedTransactionListener {

    private final static boolean debug = JMSXAWrappedConnectionFactoryImpl.debug; 

    private Session session_;
    private XAResource nonxaresource_ = null;
    private JMSXAWrappedXAResourceImpl xaresource_ = null;

    private boolean ignoreSessionCloseForRAR_ = false;

    private boolean delaySessionCloseForRAR_ = false;
    private JMSXAWrappedLock lock_ = null;
    private Hashtable transactions_ = new Hashtable();
    private boolean markClosed_ = false;

    private boolean closed_ = false;

    private JMSXAWrappedQueueConnectionImpl wconn_ = null;

    /** private constructor - disallow null constructor */
    private JMSXAWrappedQueueSessionImpl() {}


    public JMSXAWrappedQueueSessionImpl(QueueConnection qconn,
                                   boolean transacted, int ackMode,
                                   JMSXAWrappedQueueConnectionImpl wconn) throws JMSException {
        wconn_ = wconn;
        //An XAQueueSession is created in this case
        //Per std javax.jms method signatures only the createXAQueueSession() does this
        if (qconn instanceof XAQueueConnection) {
            session_ = ((XAQueueConnection)qconn).createXAQueueSession();
            xaresource_ = new JMSXAWrappedXAResourceImpl(((XASession)session_).getXAResource(),
           			                                     true,
                                                         wconn.getJMSXAWrappedConnectionFactory(),
                                                         wconn.getUsername(), wconn.getPassword());

            delaySessionCloseForRAR_ = JMSXAWrappedXAResourceImpl.isSystemPropertySetFor(
                                         "delaySessionCloseForExternalJMSXAResource", 
                                         xaresource_.getDelegatedXAResource().getClass().getName());

            ignoreSessionCloseForRAR_ = JMSXAWrappedXAResourceImpl.isSystemPropertySetFor(
                                         "ignoreSessionCloseForExternalJMSXAResource", 
                                         xaresource_.getDelegatedXAResource().getClass().getName());


            if (delaySessionCloseForRAR_) {
                log("Info:", "Enable delay Session.close() for "
                     +xaresource_.getDelegatedXAResource().getClass().getName());
                lock_ = new JMSXAWrappedLock();
                xaresource_.setTransactionListener(this); 
            }
                
        }
        else { 
            //A QueueSession is created in this case
            //Per std javax.jms method signatures only the createQueueSession(transacted, ackMode) can be used
            session_ = qconn.createQueueSession(transacted, ackMode);
            nonxaresource_ = new XAResourceUnsupportedImpl();
        }
 
    }    

    protected boolean delaySessionClose() {
        return delaySessionCloseForRAR_;
    }

    public void beforeTransactionStart() throws JMSException {
        lock_.acquireLock(); 
        if (closed_)  {
            throw new javax.jms.IllegalStateException("JMSXWrapped Session has been closed");
        }
        if (markClosed_)  {
            throw new javax.jms.IllegalStateException("JMSXAWrapped Session is closed");
        }
    }

    public void afterTransactionStart(Xid foreignXid, boolean started) {
        if (started) transactions_.put(foreignXid, ""); 
        lock_.releaseLock();
    }

    public void beforeTransactionComplete() {
        lock_.acquireLock();
    }

    public void afterTransactionComplete(Xid foreignXid, boolean completed) {
        try {

        if (completed) {
            if (debug) {
                if (transactions_.get(foreignXid) == null) {
                    log("Warning:",
                    "afterTransactionComplete: transaction Xid="+foreignXid +" not found");
                }
            }
            transactions_.remove(foreignXid);
        }
        if (transactions_.isEmpty()) { 
            if (markClosed_) {
                dlog("All transaction completed, hard close session "
                      +session_+" "+session_.getClass().getName());
                closed_ = true;
            }
        }

        } finally {
        lock_.releaseLock();
        }

        if (closed_) {
           try {
           hardClose();
           } catch (JMSException e) {
           log("Warning:", e);
           }
        }
    }

    public void close() throws JMSException {
        
        if (delaySessionCloseForRAR_) {

            if (session_.getMessageListener() != null) {
            log("Info:", "Session MessageListener set. No delay session close for session "
                  + session_+ " "+ session_.getClass().getName());
            } else {
 
            lock_.acquireLock();
            try {

            if (transactions_.isEmpty() || xaresource_ == null) {
               closed_ = true;
            } else {
               markClosed_ = true;
            }

            } finally {
            lock_.releaseLock();
            }

            if (closed_) hardClose();
            return;

            }
        }

        if (ignoreSessionCloseForRAR_) {
            hardClose(false);
            return;
        }
        
        hardClose();
    }

    private void hardClose() throws JMSException { 
        hardClose(true);
    }

    private void hardClose(boolean closerar) throws JMSException { 
        session_.close();
        dlog("hard closed session:"+session_ +" "+session_.getClass().getName());
        if (xaresource_ != null && closerar) {
            xaresource_.close();
        }
        closed_ = true;
        if (delaySessionCloseForRAR_) wconn_.removeSession(this);
    }

    public Session getSession() throws JMSException {
        if (closed_)  {
            throw new javax.jms.IllegalStateException("JMSXWrapped Session has been closed");
        }
        if (markClosed_)  {
            throw new javax.jms.IllegalStateException("JMSXAWrapped Session is closed");
        }
        return session_;
    }

    public XAResource getXAResource() {
        if (session_ instanceof XASession) {
            return xaresource_;
        } else {
            //Either a QueueSession or TopicSession
            //So return the dummy XAResource created above
            return nonxaresource_;
        }
    }

    public QueueSession getQueueSession() throws JMSException {
        if (closed_)  {
            throw new javax.jms.IllegalStateException("JMSXWrapped Session has been closed");
        }
        if (markClosed_)  {
            throw new javax.jms.IllegalStateException("JMSXAWrapped Session is closed");
        }
        if (session_ instanceof XASession) {
            return ((XAQueueSession)session_).getQueueSession();
        } else {
            //If it's not an XASession, session_ itself is a regular QueueSession
            return (QueueSession)session_;
        }
    }

    private final static void dlog(String msg) {
        if (debug) log("Info:", msg);
    }

    private final static void log(String level, Exception e) {
        log(level, e.getMessage());
        e.printStackTrace();
    }
    private final static void log(String level, String msg) {
        System.out.println(level+ " "+"JMSXAWrappedQueueSessionImpl: " + msg);
    }

}

