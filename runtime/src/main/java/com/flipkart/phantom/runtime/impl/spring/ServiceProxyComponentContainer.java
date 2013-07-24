/*
 * Copyright 2012-2015, Flipkart Internet Pvt Ltd. All rights reserved.
 * 
 * This software is the confidential and proprietary information of Flipkart Internet Pvt Ltd. ("Confidential Information").  
 * You shall not disclose such Confidential Information and shall use it only in accordance with the terms of the license 
 * agreement you entered into with Flipkart.    
 * 
 */
package com.flipkart.phantom.runtime.impl.spring;

import com.flipkart.phantom.runtime.ServiceProxyFrameworkConstants;
import com.flipkart.phantom.runtime.impl.notifier.HystrixEventReceiver;
import com.flipkart.phantom.runtime.impl.server.AbstractNetworkServer;
import com.flipkart.phantom.runtime.impl.server.netty.ChannelHandlerPipelineFactory;
import com.flipkart.phantom.runtime.impl.server.netty.handler.thrift.ThriftChannelHandler;
import com.flipkart.phantom.runtime.impl.spring.admin.SPConfigServiceImpl;
import com.flipkart.phantom.runtime.spi.spring.admin.SPConfigService;
import com.flipkart.phantom.task.impl.registry.TaskHandlerRegistry;
import com.flipkart.phantom.thrift.impl.registry.ThriftProxyRegistry;
import com.flipkart.phantom.task.spi.TaskContext;
import com.flipkart.phantom.task.spi.TaskHandler;
import com.flipkart.phantom.thrift.spi.ThriftProxy;
import com.netflix.hystrix.Hystrix;
import com.netflix.hystrix.strategy.HystrixPlugins;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.support.AbstractApplicationContext;
import org.springframework.context.support.ClassPathXmlApplicationContext;
import org.springframework.context.support.FileSystemXmlApplicationContext;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.trpr.platform.core.PlatformException;
import org.trpr.platform.core.spi.event.PlatformEventProducer;
import org.trpr.platform.model.event.PlatformEvent;
import org.trpr.platform.runtime.common.RuntimeConstants;
import org.trpr.platform.runtime.common.RuntimeVariables;
import org.trpr.platform.runtime.impl.bootstrapext.spring.ApplicationContextFactory;
import org.trpr.platform.runtime.impl.config.FileLocator;
import org.trpr.platform.runtime.spi.bootstrapext.BootstrapExtension;
import org.trpr.platform.runtime.spi.component.ComponentContainer;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

/**
 * The <code>ServiceProxyComponentContainer</code> class is a ComponentContainer implementation as defined by Trooper {@link "https://github.com/regunathb/Trooper"}
 * that starts up a runtime for proxying service requests.
 * This container loads the service proxy listener from {@link ServiceProxyFrameworkConstants#SPRING_PROXY_LISTENER_CONFIG}.
 * It then locates and loads all service proxy handlers contained in files named by the value of {@link ServiceProxyFrameworkConstants#SPRING_PROXY_HANDLER_CONFIG}.
 * This container also loads all common proxy related Spring beans contained in {@link ServiceProxyFrameworkConstants#COMMON_PROXY_CONFIG} and 
 * ensures that all beans declared in Trooper ServerConstants.COMMON_SPRING_BEANS_CONFIG are available to the proxy handler beans by 
 * specifying the common beans context as the parent for each proxy app context created by this container.
 * 
 * @see org.trpr.platform.runtime.spi.component.ComponentContainer
 * @author Regunath B
 * @version 1.0, 14 Mar 2013
 */
public class ServiceProxyComponentContainer  implements ComponentContainer {
	
	/**
	 * The default Event producer bean name 
	 */
	private static final String DEFAULT_EVENT_PRODUCER = "platformEventProducer";

	/** The prefix to be added to file absolute paths when loading Spring XMLs using the FileSystemXmlApplicationContext*/
	private static final String FILE_PREFIX = "file:";	

	/** The bean names of the service proxy framework classes initialized by this container */
	private static final String TASK_REGISTRY_BEAN = "taskHandlerRegistry";
	private static final String THRIFT_PROXY_REGISTRY_BEAN = "thriftProxyRegistry";
	private static final String CONFIG_SERVICE_BEAN = "configService";
	private static final String TASK_CONTEXT_BEAN = "taskContext";

	/** Logger for this class*/
	private static final Logger LOGGER = LoggerFactory.getLogger(ServiceProxyComponentContainer.class);

	/** The common proxy handler beans context*/
	private static AbstractApplicationContext commonProxyHandlerBeansContext;    

	/**
	 * The list of ProxyHandlerConfigInfo holding all proxy handler instances loaded by this container
	 */
	private List<ProxyHandlerConfigInfo> proxyHandlerContextsList = new LinkedList<ProxyHandlerConfigInfo>();	

	/** Local reference for all BootstrapExtensionS loaded by the Container and set on this ComponentContainer*/
	private BootstrapExtension[] loadedBootstrapExtensions;

	/** The Thread's context class loader that is used in on the fly loading of proxy handler definitions */
	private ClassLoader tccl; 

	/** The taskHandlerRegistry instance */
	private TaskHandlerRegistry taskHandlerRegistry; 

	/** The thriftProxyRegistry instance */
	private ThriftProxyRegistry thriftProxyRegistry;
	
	/** The configService instance */
	private SPConfigService configService;
	
	/** The TaskContext bean instance*/
	private TaskContext taskContext;

	/**
	 * Returns the common Proxy Handler Spring beans application context that is intended as parent of all proxy handler application contexts 
	 * WARN : this method can return null if this ComponentContainer is not suitably initialized via a call to {@link #init()}
	 * @return null or the common proxy handler AbstractApplicationContext
	 */
	public static AbstractApplicationContext getCommonProxyHandlerBeansContext() {
		return ServiceProxyComponentContainer.commonProxyHandlerBeansContext;
	}

	/**
	 * Interface method implementation. Returns the fully qualified class name of this class
	 * @see org.trpr.platform.runtime.spi.component.ComponentContainer#getName()
	 */
	public String getName() {
		return this.getClass().getName();
	}

	/**
	 * Interface method implementation. Stores local references to the specified BootstrapExtension instances.
	 * @see org.trpr.platform.runtime.spi.component.ComponentContainer#setLoadedBootstrapExtensions(org.trpr.platform.runtime.spi.bootstrapext.BootstrapExtension[])
	 */
	public void setLoadedBootstrapExtensions(BootstrapExtension...bootstrapExtensions) {
		this.loadedBootstrapExtensions = bootstrapExtensions;
	}

	/**
	 * Interface method implementation. Locates and loads all configured proxy handlers.
	 * @see ComponentContainer#init()
	 */
	public void init() throws PlatformException {
		//Register HystrixEventNotifier
		if (HystrixPlugins.getInstance().getEventNotifier() == null) {
			HystrixPlugins.getInstance().registerEventNotifier(new HystrixEventReceiver());
		}
		// store the thread's context class loader for later use in on the fly loading of proxy handler app contexts
		this.tccl = Thread.currentThread().getContextClassLoader();

		// The common proxy handler beans context is loaded first using the Platform common beans context as parent
		// load this from classpath as it is packaged with the binaries
		ApplicationContextFactory defaultCtxFactory = null;
		for (BootstrapExtension be : this.loadedBootstrapExtensions) {
			if (ApplicationContextFactory.class.isAssignableFrom(be.getClass())) {
				defaultCtxFactory = (ApplicationContextFactory)be;
				break;
			}
		}

		ServiceProxyComponentContainer.commonProxyHandlerBeansContext = new ClassPathXmlApplicationContext(new String[]{ServiceProxyFrameworkConstants.COMMON_PROXY_CONFIG}, 
				defaultCtxFactory.getCommonBeansContext());
		// add the common proxy beans independently to the list of proxy handler contexts as common handlers are declared there
		this.proxyHandlerContextsList.add(new ProxyHandlerConfigInfo(new File(ServiceProxyFrameworkConstants.COMMON_PROXY_CONFIG), null, ServiceProxyComponentContainer.commonProxyHandlerBeansContext));		

		((SPConfigServiceImpl)ServiceProxyComponentContainer.commonProxyHandlerBeansContext.getBean("configService")).setComponentContainer(this);
		//Get the Config Service Bean
		this.configService = (SPConfigServiceImpl)ServiceProxyComponentContainer.commonProxyHandlerBeansContext.getBean(ServiceProxyComponentContainer.CONFIG_SERVICE_BEAN);
				
		// Load additional if runtime nature is "server". This context is the new common beans context
		if (RuntimeVariables.getRuntimeNature().equalsIgnoreCase(RuntimeConstants.SERVER)) {
			ServiceProxyComponentContainer.commonProxyHandlerBeansContext = new ClassPathXmlApplicationContext(new String[]{ServiceProxyFrameworkConstants.COMMON_PROXY_SERVER_NATURE_CONFIG},
					ServiceProxyComponentContainer.commonProxyHandlerBeansContext);
			// now add the common server nature proxy hander beans to the contexts list
			this.proxyHandlerContextsList.add(new ProxyHandlerConfigInfo(new File(ServiceProxyFrameworkConstants.COMMON_PROXY_SERVER_NATURE_CONFIG), null, 
					ServiceProxyComponentContainer.commonProxyHandlerBeansContext));
		}

		//Get the TaskHandlerRegistry Bean
		this.taskHandlerRegistry = (TaskHandlerRegistry)ServiceProxyComponentContainer.commonProxyHandlerBeansContext.getBean(ServiceProxyComponentContainer.TASK_REGISTRY_BEAN);
		//Get the TaskHandlerRegistry Bean
		this.thriftProxyRegistry = (ThriftProxyRegistry)ServiceProxyComponentContainer.commonProxyHandlerBeansContext.getBean(ServiceProxyComponentContainer.THRIFT_PROXY_REGISTRY_BEAN);

        // locate and load the individual proxy handler bean XML files using the common proxy handler beans context as parent
        File[] proxyHandlerBeansFiles = FileLocator.findFiles(ServiceProxyFrameworkConstants.SPRING_PROXY_HANDLER_CONFIG);
        for (File proxyHandlerBeansFile : proxyHandlerBeansFiles) {
            ProxyHandlerConfigInfo proxyHandlerConfigInfo = new ProxyHandlerConfigInfo(proxyHandlerBeansFile);
            // load the proxy handler's appcontext
            this.loadProxyHandlerContext(proxyHandlerConfigInfo);
        }

		// add the proxy listener beans to the contexts list (these have the thrift handlers)
		File[] proxyListenerBeanFiles = FileLocator.findFiles(ServiceProxyFrameworkConstants.SPRING_PROXY_LISTENER_CONFIG);			
		for (File proxyListenerBeanFile : proxyListenerBeanFiles) {
			// locate and load the service proxy listener defined in the file identified by {@link ServiceProxyFrameworkConstants#SPRING_PROXY_LISTENER_CONFIG}
			AbstractApplicationContext listenerContext = new FileSystemXmlApplicationContext(
					new String[] {FILE_PREFIX + proxyListenerBeanFile.getAbsolutePath()}, 
					ServiceProxyComponentContainer.commonProxyHandlerBeansContext);
			this.proxyHandlerContextsList.add(new ProxyHandlerConfigInfo(proxyListenerBeanFile, null, listenerContext));
		}		


		// Add all TaskHandler instances to the TaskHandlerRegistry
		for(ProxyHandlerConfigInfo proxyHandlerConfigInfo: this.proxyHandlerContextsList) {
			//Thrift Handlers loading
			//Load Pipeline factory bean(s)
			String[] pipelineFactoryBeanIDs = proxyHandlerConfigInfo.getProxyHandlerContext().getBeanNamesForType(ChannelHandlerPipelineFactory.class);
			for(String pipelineFactoryBeanID : pipelineFactoryBeanIDs) {
				ChannelHandlerPipelineFactory pipelineFactory = (ChannelHandlerPipelineFactory) proxyHandlerConfigInfo.getProxyHandlerContext().getBean(pipelineFactoryBeanID);
				//Check if the pipelineFactory has a ThriftHandler
				boolean containsThriftHandler = false;
				ThriftChannelHandler thriftChannelHandler = null;
				for(String channelHandlerName : pipelineFactory.getChannelHandlersMap().keySet()) {
					if(pipelineFactory.getChannelHandlersMap().get(channelHandlerName) instanceof ThriftChannelHandler) {
						if(!containsThriftHandler) {
							containsThriftHandler = true;
							thriftChannelHandler = (ThriftChannelHandler) pipelineFactory.getChannelHandlersMap().get(channelHandlerName);
						} else {
							throw new PlatformException("Multiple Thrift Handlers not allowed in the same pipelineFactory");
						}
					}
				}
				if(containsThriftHandler) { //Add the ThriftHandler to registry
					try {
						thriftChannelHandler.getThriftProxy().init();
						thriftChannelHandler.getThriftProxy().setStatus(ThriftProxy.ACTIVE);	
					} catch (Exception e) {
						// TODO see if there are ThriftProxyS that can fail init but still permit others to load and the proxy can become active
						LOGGER.error("Error initing ThriftProxy {} . Error is : " + e.getMessage(),thriftChannelHandler.getThriftProxy().getName(), e);
						throw new PlatformException("Error initing ThriftProxy : " + thriftChannelHandler.getThriftProxy().getName(), e);
					}
					this.thriftProxyRegistry.registerThriftProxy(thriftChannelHandler.getThriftProxy());
				}
			}
		}

		//Inject into configService NetworkServers
		for(ProxyHandlerConfigInfo proxyHandlerConfigInfo: this.proxyHandlerContextsList) {
			Map<String, AbstractNetworkServer> networkServerBeansMap = proxyHandlerConfigInfo.getProxyHandlerContext().getBeansOfType(AbstractNetworkServer.class);
			for(AbstractNetworkServer server : networkServerBeansMap.values()) {
                this.configService.getDeployedNetworkServers().add(server);
			}
		}
		
		LOGGER.debug("Registered task handlers are:");
		for(TaskHandler taskHandler: this.taskHandlerRegistry.getAllTaskHandlers()) {
			LOGGER.debug(taskHandler.getName()+" "+Arrays.asList(taskHandler.getCommands()));
		}	
		LOGGER.debug("Registered thrift proxies are:");
		for(ThriftProxy thriftProxy: this.thriftProxyRegistry.getAllThriftProxies()) {
			LOGGER.debug(thriftProxy.getName()+" "+Arrays.asList(thriftProxy.getProcessMap().keySet()));
		}	
	}

	/**
	 * Interface method implementation. Destroys the Spring application context containing loaded proxy handler definitions.
	 * @see ComponentContainer#destroy()
	 */
	public void destroy() throws PlatformException {
		// reset the Hystrix instance
		Hystrix.reset();
		// now shutdown all task handlers
		for (ProxyHandlerConfigInfo proxyHandlerConfigInfo : this.proxyHandlerContextsList) {
			String[] taskHandlerBeanIds = proxyHandlerConfigInfo.getProxyHandlerContext().getBeanNamesForType(TaskHandler.class);
			for(String taskHandlerBeanId : taskHandlerBeanIds) {
				TaskHandler taskHandler = (TaskHandler) proxyHandlerConfigInfo.getProxyHandlerContext().getBean(taskHandlerBeanId);
				// shutdown the TaskHandler
				try {
					taskHandler.shutdown(this.taskContext);
					taskHandler.setStatus(TaskHandler.INACTIVE);
				} catch (Exception e) {
					// just log a warning and continue with shutting down other task handlers
					LOGGER.warn("Error shutting down TaskHandler {} . Error is : " + e.getMessage(),taskHandler.getName(), e);					
				}
			}
			// finally close the context
			proxyHandlerConfigInfo.getProxyHandlerContext().close();
		}
		this.proxyHandlerContextsList = null;		
		// now shutdown the thrift proxy instances
		for (ThriftProxy thriftProxy : this.thriftProxyRegistry.getAllThriftProxies()) {
			try {
				thriftProxy.shutdown();
				thriftProxy.setStatus(ThriftProxy.INACTIVE);
			} catch (Exception e) {
				// just log a warning and continue with shutting down other thrift proxies
				LOGGER.warn("Error shutting down ThriftProxy {} . Error is : " + e.getMessage(),thriftProxy.getName(), e);					
			}
		}
	}

	/**
	 * Interface method implementation. Publishes the specified event to using a named bean DEFAULT_EVENT_PRODUCER looked up from the 
	 * common proxy handler context (i.e. ServiceProxyFrameworkConstants.COMMON_PROXY_CONFIG).
	 * Note that typically no consumers are registered when running this container
	 */ 
	public void publishEvent(PlatformEvent event) {
		PlatformEventProducer publisher= (PlatformEventProducer)ServiceProxyComponentContainer.commonProxyHandlerBeansContext.getBean(DEFAULT_EVENT_PRODUCER);
		publisher.publishEvent(event);
	}

	/**
	 * Interface method implementation. Publishes the specified event using the {@link #publishEvent(PlatformEvent)} method
	 * @see ComponentContainer#publishBootstrapEvent(PlatformEvent)
	 */
	public void publishBootstrapEvent(PlatformEvent bootstrapEvent) {	
		this.publishEvent(bootstrapEvent);
	}

	/**
	 * Interface method implementation. Loads/Reloads proxy handler(s) defined in the specified {@link FileSystemResource} 
	 * @see org.trpr.platform.runtime.spi.component.ComponentContainer#loadComponent(org.springframework.core.io.Resource)
	 */
	public void loadComponent(Resource resource) {
		if (!FileSystemResource.class.isAssignableFrom(resource.getClass()) || 
				!((FileSystemResource)resource).getFilename().equalsIgnoreCase(ServiceProxyFrameworkConstants.SPRING_PROXY_HANDLER_CONFIG)) {
			throw new UnsupportedOperationException("Proxy handers can be loaded only from files by name : " + 
					ServiceProxyFrameworkConstants.SPRING_PROXY_HANDLER_CONFIG + ". Specified resource is : " + resource.toString());
		}
		loadProxyHandlerContext(new ProxyHandlerConfigInfo(((FileSystemResource)resource).getFile()));
	}

	/**
	 * Loads the proxy handler context from path specified in the ProxyHandlerConfigInfo. Looks for file by name ServiceProxyFrameworkConstants.SPRING_PROXY_HANDLER_CONFIG. 
	 * @param proxyHandlerConfigInfo containing absolute path to the proxy handler's configuration location i.e. folder
	 */
	private void loadProxyHandlerContext(ProxyHandlerConfigInfo proxyHandlerConfigInfo) {
		// check if a context exists already for this config path 
		for (ProxyHandlerConfigInfo loadedProxyHandlerConfigInfo : this.proxyHandlerContextsList) {
			if (loadedProxyHandlerConfigInfo.equals(proxyHandlerConfigInfo)) {
				proxyHandlerConfigInfo = loadedProxyHandlerConfigInfo;
				break;
			}
		}
		if (proxyHandlerConfigInfo.getProxyHandlerContext() != null) {
			// close the context and remove from list
			proxyHandlerConfigInfo.getProxyHandlerContext().close();
			this.proxyHandlerContextsList.remove(proxyHandlerConfigInfo);
		}
		ClassLoader proxyHandlerCL = this.tccl;
		// check to see if the proxy has handler and dependent binaries deployed outside of the runtime class path. If yes, include them using a custom URL classloader.
		File customLibPath = new File (proxyHandlerConfigInfo.getProxyHandlerConfigXML().getParentFile(), ProxyHandlerConfigInfo.BINARIES_PATH);
		if (customLibPath.exists() && customLibPath.isDirectory()) {
			try {
				File[] libFiles = customLibPath.listFiles();
				URL[] libURLs = new URL[libFiles.length];
				for (int i=0; i < libFiles.length; i++) {
					libURLs[i] = new URL(ProxyHandlerConfigInfo.FILE_PREFIX + libFiles[i].getAbsolutePath());
				}
				proxyHandlerCL = new URLClassLoader(libURLs, this.tccl);
			} catch (MalformedURLException e) {
				throw new PlatformException(e);
			}
		} 
		// now load the proxy handler context and add it into the proxyHandlerContextsList list
		proxyHandlerConfigInfo.loadProxyHandlerContext(proxyHandlerCL);
		this.proxyHandlerContextsList.add(proxyHandlerConfigInfo);
		
		//Init the TaskHandler
		String[] taskHandlerBeanIds = proxyHandlerConfigInfo.getProxyHandlerContext().getBeanNamesForType(TaskHandler.class);
		for(String taskHandlerBeanId : taskHandlerBeanIds) {
			TaskHandler taskHandler = (TaskHandler) proxyHandlerConfigInfo.getProxyHandlerContext().getBean(taskHandlerBeanId);
			// init the TaskHandler
			try {
				this.taskContext = (TaskContext)ServiceProxyComponentContainer.commonProxyHandlerBeansContext.getBean(ServiceProxyComponentContainer.TASK_CONTEXT_BEAN);
				LOGGER.info("Initing TaskHandler: "+taskHandler.getName());
				taskHandler.init(this.taskContext);
				taskHandler.setStatus(TaskHandler.ACTIVE);
			} catch (Exception e) {
				// TODO see if there are TaskHandlerS that can fail init but still permit others to load and the proxy can become active
				LOGGER.error("Error initing TaskHandler {} . Error is : " + e.getMessage(),taskHandler.getName(), e);
				throw new PlatformException("Error initing TaskHandler : " + taskHandler.getName(), e);
			}
			//Register the taskHandler for all the commands it handles
			this.taskHandlerRegistry.registerTaskHandler(taskHandler);
			//Add the file path to SPConfigService (for configuration console)
			this.configService.addTaskHandlerConfigPath(proxyHandlerConfigInfo.getProxyHandlerConfigXML(), taskHandler);
		}
	}
}