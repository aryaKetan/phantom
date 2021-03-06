/*
 * Copyright 2012-2015, the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.flipkart.phantom.runtime.impl.server.netty.handler.http;

import com.flipkart.phantom.http.impl.HttpProxy;
import com.flipkart.phantom.http.impl.HttpProxyExecutor;
import com.flipkart.phantom.http.impl.HttpProxyExecutorRepository;
import com.flipkart.phantom.task.utils.RequestLogger;
import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.util.EntityUtils;
import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.channel.*;
import org.jboss.netty.channel.group.ChannelGroup;
import org.jboss.netty.handler.codec.http.DefaultHttpResponse;
import org.jboss.netty.handler.codec.http.HttpRequest;
import org.jboss.netty.handler.codec.http.HttpResponseStatus;
import org.jboss.netty.handler.codec.http.HttpVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.util.Assert;

import java.util.HashMap;
import java.util.Map;

/**
 * <code>RoutingHttpChannelHandler</code> is a sub-type of {@link SimpleChannelHandler} that routes Http requests to one or more {@link HttpProxy} instances.
 *
 * @author Regunath B
 * @version 1.0, 6 Sep 2013
 */

public abstract class RoutingHttpChannelHandler extends SimpleChannelUpstreamHandler implements InitializingBean {

    /** The empty routing key which is default*/
    public static final String ALL_ROUTES = "";

    /** Constant String literals in the Http protocol */
    private static final String ENCODING_HEADER = "Transfer-Encoding";
    
	/** Logger for this class*/
	private static final Logger LOGGER = LoggerFactory.getLogger(RoutingHttpChannelHandler.class);
	
	/** The default channel group*/
	private ChannelGroup defaultChannelGroup;
	
	/** The TaskRepository to lookup TaskHandlerExecutors from */
	private HttpProxyExecutorRepository repository;

    /** The HTTP proxy handler map*/
    private Map<String, String> proxyMap = new HashMap<String, String>();

    /** The default HTTP proxy handler */
    private String defaultProxy;
    
	/**
	 * Interface method implementation. Checks if all mandatory properties have been set
	 * @see org.springframework.beans.factory.InitializingBean#afterPropertiesSet()
	 */
	public void afterPropertiesSet() throws Exception {		
		Assert.notNull(this.defaultProxy, "The 'defaultProxy' may not be null");	
		// add the default proxy for all routes i.e. default
		this.proxyMap.put(RoutingHttpChannelHandler.ALL_ROUTES, defaultProxy);
	}
	
	/**
	 * Overriden superclass method. Adds the newly created Channel to the default channel group and calls the super class {@link #channelOpen(ChannelHandlerContext, ChannelStateEvent)} method
	 * @see org.jboss.netty.channel.SimpleChannelUpstreamHandler#channelOpen(org.jboss.netty.channel.ChannelHandlerContext, org.jboss.netty.channel.ChannelStateEvent)
	 */
	public void channelOpen(ChannelHandlerContext ctx, ChannelStateEvent event) throws Exception {
		super.channelOpen(ctx, event);
    }

	/**
	 * Interface method implementation. Reads and processes Http commands sent to the service proxy. Expects data in the Http protocol.
	 * @see org.jboss.netty.channel.SimpleChannelUpstreamHandler#handleUpstream(org.jboss.netty.channel.ChannelHandlerContext, org.jboss.netty.channel.ChannelEvent)
	 */
	public void messageReceived(ChannelHandlerContext ctx, MessageEvent messageEvent) throws Exception {

        HttpRequest request = (HttpRequest) messageEvent.getMessage();
        LOGGER.debug("Request is: " + request.getMethod() + " " + request.getUri());

        // get data
        ChannelBuffer inputBuffer = request.getContent();
        byte[] requestData = new byte[inputBuffer.readableBytes()];
        inputBuffer.readBytes(requestData, 0, requestData.length);

        // executor
        String proxy = this.proxyMap.get(this.getRoutingKey(request));
        if (proxy == null) {
        	proxy = this.proxyMap.get(RoutingHttpChannelHandler.ALL_ROUTES);
        	LOGGER.info("Routing key for : " + request.getUri() + " returned null. Using default proxy instead.");
        }
        HttpProxyExecutor executor = this.repository.getHttpProxyExecutor(proxy,request.getMethod().toString(),request.getUri(),requestData);

        // excute
        HttpResponse response = null;
        try {
            response = executor.execute();
        } catch (Exception e) {
            LOGGER.error("Error in executing HTTP request:" + proxy + " URI:" + request.getUri(), e);
            throw new RuntimeException("Error in executing HTTP request:" + proxy + " URI:" + request.getUri(), e);
        } finally {
            RequestLogger.log(executor);
        }

        // send response
        writeCommandExecutionResponse(ctx,messageEvent,response);
	}

	/**
	 * Interface method implementation. Closes the underlying channel after logging a warning message
	 * @see org.jboss.netty.channel.SimpleChannelHandler#exceptionCaught(org.jboss.netty.channel.ChannelHandlerContext, org.jboss.netty.channel.ExceptionEvent)
	 */
	public void exceptionCaught(ChannelHandlerContext ctx, ExceptionEvent event) throws Exception {
		LOGGER.warn("Exception {} thrown on Channel {}. Disconnect initiated", event, event.getChannel());
        event.getCause().printStackTrace();
		event.getChannel().close();
	}
	
	/**
	 * Returns the routing key to use for proxy selection. Sub-types may use the passed-in request data attributes to determine routing
	 * @param request the HttpRequest object
	 * @return the routing key to identify the proxy
	 */
	protected abstract String getRoutingKey(HttpRequest request);    
	
    /**
     * Writes the specified TaskResult data to the channel output. Only the raw output data is written and rest of the TaskResult fields are ignored 
     * @param ctx the ChannelHandlerContext
     * @param event the ChannelEvent
     * @throws Exception in case of any errors
     */
    private void writeCommandExecutionResponse(ChannelHandlerContext ctx, ChannelEvent event, HttpResponse response) throws Exception {
        // Don't write anything if the response is null
        if (response == null) {
            return;
        }
        org.jboss.netty.handler.codec.http.HttpResponse httpResponse = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.valueOf(response.getStatusLine().getStatusCode()));
        // write headers
        for (Header header : response.getAllHeaders()) {
            if (!header.getName().equals(ENCODING_HEADER)) {
                httpResponse.setHeader(header.getName(),header.getValue());
            }
        }
        // write entity
        HttpEntity responseEntity = response.getEntity();
        byte[] responseData = EntityUtils.toByteArray(responseEntity);
        httpResponse.setContent(ChannelBuffers.copiedBuffer(responseData));
        // write response
        event.getChannel().write(httpResponse).addListener(ChannelFutureListener.CLOSE);
    }
    
	/** Start Getter/Setter methods */
	public ChannelGroup getDefaultChannelGroup() {
		return this.defaultChannelGroup;
	}
	public void setDefaultChannelGroup(ChannelGroup defaultChannelGroup) {
		this.defaultChannelGroup = defaultChannelGroup;
	}
	public HttpProxyExecutorRepository getRepository() {
		return this.repository;
	}
	public void setRepository(HttpProxyExecutorRepository repository) {
		this.repository = repository;
	}
	public Map<String, String> getProxyMap() {
		return this.proxyMap;
	}
	public void setProxyMap(Map<String, String> proxyMap) {
		this.proxyMap = proxyMap;
	}
	public String getDefaultProxy() {
		return this.defaultProxy;
	}
	public void setDefaultProxy(String defaultProxy) {
		this.defaultProxy = defaultProxy;
	}		
	/** End Getter/Setter methods */

}
