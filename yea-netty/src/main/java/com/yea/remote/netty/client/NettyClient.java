/**
 * Copyright 2017 伊永飞
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.yea.remote.netty.client;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

import com.yea.core.base.id.UUIDGenerator;
import com.yea.core.exception.constants.YeaErrorMessage;
import com.yea.core.loadbalancer.BalancingNode;
import com.yea.core.remote.AbstractClient;
import com.yea.core.remote.client.ClientRegister;
import com.yea.core.remote.constants.RemoteConstants;
import com.yea.core.remote.exception.RemoteException;
import com.yea.core.remote.struct.Header;
import com.yea.core.remote.struct.Message;
import com.yea.core.util.NetworkUtils;
import com.yea.core.util.ScheduledExecutor;
import com.yea.loadbalancer.ClientRegisterServerList;
import com.yea.loadbalancer.LoadBalancerBuilder;
import com.yea.loadbalancer.NettyPing;
import com.yea.loadbalancer.config.CommonClientConfigKey;
import com.yea.loadbalancer.config.DefaultClientConfigImpl;
import com.yea.loadbalancer.rule.WeightedHashRule;
import com.yea.remote.netty.AbstractNettyEndpoint;
import com.yea.remote.netty.balancing.RemoteClient;
import com.yea.remote.netty.client.send.UnavailableSend;
import com.yea.remote.netty.handle.NettyChannelHandler;
import com.yea.remote.netty.send.SendHelperRegister;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.AdaptiveRecvByteBufAllocator;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;

/**
 * Netty客户端启动器
 * @author yiyongfei
 */
public class NettyClient extends AbstractNettyEndpoint {
	private static final Logger LOGGER = LoggerFactory.getLogger(NettyClient.class);
	private List<Map<String, ChannelHandler>> listHandler = null;
    private List<SocketAddress> listSocketAddress = null;
   
	public void connect() throws Exception {
		if(StringUtils.isEmpty(this.getHost())){
			this.setHost(NetworkUtils.getIp());
    	}
		
		if(listSocketAddress != null && listSocketAddress.size() > 0){
    		List<Future<Boolean>> listFuture = new ArrayList<Future<Boolean>>();
    		//批量连接服务器时使用线程池，每新起一个线程来连接
        	ExecutorService executor = Executors.newCachedThreadPool(ScheduledExecutor.getThreadFactory("NettyClient"));
    		for(final SocketAddress socketAddress : listSocketAddress) {
    			Future<Boolean> future = executor.submit(new Callable<Boolean>() {
					@Override
					public Boolean call() throws Exception {
						connect(socketAddress);
						return true;
					}
				});
				listFuture.add(future);
    		}
    		boolean isConnect = false;
    		long startTime = new Date().getTime();
    		while(!isConnect){
    			TimeUnit.MILLISECONDS.sleep(5 * 1000);
    			for(Future<Boolean> future : listFuture){
    				try {
    					isConnect = future.get(1000, TimeUnit.MILLISECONDS);
						if(isConnect) {
							break;
						}
					} catch (Exception e) {
					}
    			}
				if (new Date().getTime() - startTime > 2 * 60 * 1000) {
					break;
				}
    		}
    	}
		
		ClientRegister.getInstance().registerEndpoint(getRegisterName(), this);
		
		if (this.getDispatcher() != null) {
			try{
				//注册消费者是需要监听提供者，此时会有NODEADD动作，所以注册环节在连接服务后才进行
				this.getDispatcher().register(this);
			} catch (Throwable ex){
			}
		}
		
    }
	
	public void connect(SocketAddress socketAddress) throws Exception {
		if (!loadBalancer.contains(socketAddress)) {
			Client client = new Client();
			if(StringUtils.isEmpty(this.getHost())){
				this.setHost(NetworkUtils.getIp());
	    	}
			client.setRegisterName(this.getRegisterName());
			client.setHost(this.getHost());
			client.setPort(this.getPort());
			client.setApplicationContext(this.getApplicationContext());
			client.connect(socketAddress);
		}
    }
	
	public void disconnect() throws Exception {
		List<BalancingNode> tmp = new ArrayList<BalancingNode>();
		tmp.addAll(loadBalancer.getAllNodes());
		for (BalancingNode node : tmp) {
			disconnect(node.getSocketAddress());
		}
		if (this.getDispatcher() != null) {
			try {
				this.getDispatcher().logout(this);
			} catch (Throwable ex) {
			}
		}
	}
	
	public void disconnect(SocketAddress socketAddress) throws Exception {
		Collection<BalancingNode> nodes = loadBalancer.chooseNode(socketAddress, false);
		if (nodes != null && nodes.size() > 0) {
			for (BalancingNode node : nodes) {
				if (!((Client) ((RemoteClient) node).getPoint()).isStop()) {
					this.unregisterNode(node);
					((Client) ((RemoteClient) node).getPoint()).stop();
				}
				
				node = null;
			}
		}
	}

	public List<Map<String, ChannelHandler>> getListHandler() {
        return listHandler;
    }

    public void setListHandler(List<Map<String, ChannelHandler>> listHandler) {
        this.listHandler = listHandler;
    }

    public List<SocketAddress> getListSocketAddress() {
		return listSocketAddress;
	}

	public void setListSocketAddress(List<SocketAddress> listSocketAddress) {
		this.listSocketAddress = listSocketAddress;
	}

	@Override
	protected void initLoadBalancer() {
		loadBalancer = LoadBalancerBuilder.newBuilder().withRule(new WeightedHashRule()).withPing(new NettyPing())
				.withClientConfig(DefaultClientConfigImpl.getClientConfigWithDefaultValues()
						.setClientName(this.getRegisterName()).set(CommonClientConfigKey.NIWSServerListClassName,
								ClientRegisterServerList.class.getName()))
				.buildDynamicServerListLoadBalancer();
	}
	
	/**
	 * Netty客户端，注册节点时需要设置发送失败后的处理
	 */
	@Override
	protected void registerNode(BalancingNode node) {
		super.registerNode(node);
		SendHelperRegister.registerInstance((RemoteClient) node, new UnavailableSend()).setBatchSize(32);
	}
	
	final class Client extends AbstractClient {
		private ExecutorService executor = Executors.newCachedThreadPool(ScheduledExecutor.getThreadFactory("NettyClient.connect"));
		private EventLoopGroup group = null;
		// 客户端所连接的远程服务器地址
		private SocketAddress remoteAddress;

		private void connect(final SocketAddress socketAddress) throws Exception {
			if (!super.isConnectSuccess()) {
				super._Disconnected();
				// 启动一个线程连接指定地址的服务器
				// 为什么新启线程：连接成功后，服务会sync，处理线程会处于等待状况，所以需要新线程来开启服务
				executor.submit(new ConnectRunnable(socketAddress));
				
				while (true) {
					if (super.isConnected()) {
						break;
					} else {
						TimeUnit.MILLISECONDS.sleep(50);
					}
				}
				// 连接不成功时，判断连接是否有超时，若超时抛出异常，否则等候2秒后重连
				if (!super.isConnectSuccess()) {
					TimeUnit.MILLISECONDS.sleep(5 * 1000);
					if (!super.isConnectSuccess()) {
						LOGGER.warn("连接服务器（" + remoteAddress + "）不成功，准备重新连接！");
						TimeUnit.MILLISECONDS.sleep(15 * 1000);
						if (!this.isStop()) {
							connect(socketAddress);
						}
					} else {
						super._Notstop();
						LOGGER.info("连接服务器（" + remoteAddress + "）成功！");
					}
				} else {
					super._Notstop();
					LOGGER.info("连接服务器（" + remoteAddress + "）成功！");
				}
			}

		}

		void disconnect() throws Exception {
			try {
				if (group != null) {
					group.shutdownGracefully();
				}
				LOGGER.info("远程连接（" + remoteAddress + "）关闭完成！");
			} finally {
				super._ConnectFailure();
			}
		}

		void stop() throws Exception {
			super._Stop();
			super._Disconnected();
			disconnect();
		}

		private void _connect(SocketAddress socketAddress) throws Exception {
			super.getConnectLock().lock();
			if (super.isConnectSuccess()) {
				return;
			}
			try {
				group = new NioEventLoopGroup((int) Math.floor(Runtime.getRuntime().availableProcessors()));
				
				// 配置客户端NIO线程组
				Bootstrap bootstrap = new Bootstrap();
				/**
				 * 1、FixedRecvByteBufAllocator：固定长度的接收缓冲区分配器，
				 * 由它分配的ByteBuf长度都是固定大小的，并不会根据实际数据报的大小动态收缩。但是，如果容量不足，支持动态扩展。
				 * 2、AdaptiveRecvByteBufAllocator：容量动态调整的接收缓冲区分配器，
				 * 它会根据之前Channel接收到的数据报大小进行计算，如果连续填充满接收缓冲区的可写空间，则动态扩展容量。
				 * 如果连续2次接收到的数据报都小于指定值，则收缩当前的容量，以节约内存。
				 */
				bootstrap.group(group).channel(NioSocketChannel.class).option(ChannelOption.TCP_NODELAY, true)
						.option(ChannelOption.SO_KEEPALIVE, true)
						.option(ChannelOption.SO_REUSEADDR, true)
						.option(ChannelOption.SO_RCVBUF, 128 * 1024)
						.option(ChannelOption.SO_SNDBUF, 16 * 1024)
						.option(ChannelOption.WRITE_SPIN_COUNT, 32)
						.option(ChannelOption.WRITE_BUFFER_HIGH_WATER_MARK, 128 * 1024)
						.option(ChannelOption.WRITE_BUFFER_LOW_WATER_MARK, 64 * 1024)
						.option(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
						.option(ChannelOption.RCVBUF_ALLOCATOR, AdaptiveRecvByteBufAllocator.DEFAULT)
						.handler(new LoggingHandler(LogLevel.WARN)).handler(new ChannelInitializer<SocketChannel>() {
							@Override
							public void initChannel(SocketChannel ch) throws Exception {
								if (listHandler != null && listHandler.size() > 0) {
									for (Map<String, ChannelHandler> map : listHandler) {
										Set<String> setKey = map.keySet();
										for (String key : setKey) {
											if (map.get(key) instanceof NettyChannelHandler) {
												NettyChannelHandler handler = (NettyChannelHandler) ((NettyChannelHandler) map
														.get(key)).clone();
												handler.setApplicationContext(getApplicationContext());
												ch.pipeline().addLast(key, handler);
											} else {
												ch.pipeline().addLast(key, map.get(key));
											}
										}
									}
									ch.pipeline().addLast(UUIDGenerator.generateString(),
											new ChannelInboundHandlerAdapter() {
										
										@Override
									    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
									        Message message = (Message) msg;
									        if (message.getHeader() != null && message.getHeader().getType() == RemoteConstants.MessageType.ACTLOOKUP_RESP.value()) {
												/* 向Client注册中心注册ActName */
									        	String registerName = (String) message.getHeader().getAttachment().get("registerName");
									        	String[] actnames = (String[]) message.getHeader().getAttachment().get("actName");
									        	ClientRegister.getInstance().registerAct(registerName, actnames);
											} else if (message.getHeader() != null && message.getHeader().getType() == RemoteConstants.MessageType.NOTIFT_STOP.value()) {
									        	LOGGER.info(ctx.channel().localAddress() + "从" + ctx.channel().remoteAddress() + "接收服务端将关闭服务的请求！");
									        	Collection<BalancingNode> collection = loadBalancer.chooseNode(ctx.channel().remoteAddress(), false);
												for (BalancingNode node : collection) {
													loadBalancer.markNodeDown(node);
												}
									        } else if (message.getHeader() != null && message.getHeader().getType() == RemoteConstants.MessageType.STOP.value()) {
									        	LOGGER.info(ctx.channel().localAddress() + "从" + ctx.channel().remoteAddress() + "接收关闭指令的请求！");
									        	Collection<BalancingNode> collection = loadBalancer.chooseNode(ctx.channel().remoteAddress(), false);
												for (BalancingNode node : collection) {
													unregisterNode(node);
												}
									        	stop();
									        } else {
									            ctx.fireChannelRead(msg);
									        }
									    }
										
										@Override
	                                    public void channelActive(ChannelHandlerContext ctx) throws Exception {
											super.channelActive(ctx);
											RemoteClient remoteClient = new RemoteClient(ctx.channel(), _instance());
											LOGGER.info("远程节点" + remoteClient.getRemoteAddress() + "已加入本地节点"+_instance().getHost()+":"+_instance().getPort()+"负载均衡池！");
											registerNode(remoteClient);
											
											/*向服务端发送查找ACT名称的请求，以向Client注册中心注册Act*/
											Message nettyMessage = new Message();
									        Header header = new Header();
									        header.setType(RemoteConstants.MessageType.ACTLOOKUP_REQ.value());
									        header.setSessionID(UUIDGenerator.generate());
									        nettyMessage.setHeader(header);
									        ctx.write(nettyMessage);
									        ctx.flush();
										}
										
										@Override
										public void channelInactive(ChannelHandlerContext ctx) throws Exception {
											super.channelInactive(ctx);
											LOGGER.info("远程节点" + ctx.channel().remoteAddress() + "已从本地节点"+_instance().getHost()+":"+_instance().getPort()+"负载均衡池移除！");
											Collection<BalancingNode> collection = loadBalancer.chooseNode(ctx.channel().remoteAddress(), false);
											for (BalancingNode node : collection) {
												unregisterNode(node);
											}
										}

									});
								} else {
									throw new RemoteException(YeaErrorMessage.ERR_FOUNDATION,
											RemoteConstants.ExceptionType.SETTING.value(),
											"ChannelHandler设置有问题，无法连接服务器！", null);
								}
							}
						});
				// 发起异步连接操作
				remoteAddress = socketAddress;
				ChannelFuture future = bootstrap.connect(remoteAddress,
						new InetSocketAddress(this.getHost(), this.getPort()));
				future.sync();

				super._Connected();
				super._ConnectSuccess();

				//会有等待远程关闭的动作
				future.channel().closeFuture().sync();
			} catch (Exception ex) {
				super._ConnectFailure();
				super._Connected();
				throw ex;
			} finally {
				super.getConnectLock().unlock();
			}
		}

		private Client _instance() {
			return this;
		}
		
		class ConnectRunnable implements Callable<Boolean> {
			private SocketAddress socketAddress;

			ConnectRunnable(SocketAddress socketAddress) {
				this.socketAddress = socketAddress;
			}

			/**
			 * @see java.lang.Runnable#run()
			 */
			@SuppressWarnings("finally")
			public Boolean call() {
				try {
					_Connection();
				} catch (Exception e) {
					LOGGER.error("连接服务器失败，抛出异常！", e);
					if (loadBalancer.contains(socketAddress)) {
						LOGGER.warn("虽连接远程节点失败，但本地节点"+_instance().getHost()+":"+_instance().getPort()+"负载均衡池里发现远程节点" + socketAddress + "存在，更改连接状态！");
						_instance()._ConnectSuccess();
						_instance()._Connected();
					}
				} finally {
					return true;
				}
			}

			private void _Connection() throws Exception {
				try {
					_connect(socketAddress);
				} finally {
					// 通道关闭，关闭服务器连接
					disconnect();
				}
				// 远程服务器关闭后，重新连接服务器，连接服务器时将另起线程
				if (!isStop()) {
					connect(socketAddress);
				}
			}
		}
	}
	
}
