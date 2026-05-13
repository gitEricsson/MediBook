package com.medibook.config;

import com.medibook.security.websocket.JwtHandshakeHandler;
import com.medibook.security.websocket.JwtHandshakeInterceptor;
import com.medibook.security.websocket.WebSocketAuthChannelInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * WebSocket / STOMP configuration.
 *
 * Scalability note:
 *   This config uses Spring's simple in-memory STOMP broker.
 *   Horizontal scalability is achieved through the existing Redis Pub/Sub layer in
 *   NotificationService: every backend replica subscribes to the same Redis channel
 *   pattern (notifications:user:*).  When a notification is published, all replicas
 *   receive it; the one that holds the user's WebSocket connection delivers it via
 *   SimpMessagingTemplate.  Replicas with no connection for that user silently no-op.
 *
 *   If Redis is replaced with RabbitMQ in the future, the broker relay config would be:
 *     registry.enableStompBrokerRelay("/queue", "/topic")
 *             .setRelayHost(...)
 *             .setRelayPort(61613);
 *
 * Destination conventions:
 *   /user/queue/notifications  — server-push to a single authenticated user
 *   /topic/admin/notifications — server-push to all connected ADMIN/SUPER_ADMIN clients
 *   /app/**                    — client-to-server messages (none used currently)
 */
@Configuration
@EnableWebSocketMessageBroker
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final JwtHandshakeInterceptor         jwtHandshakeInterceptor;
    private final JwtHandshakeHandler             jwtHandshakeHandler;
    private final WebSocketAuthChannelInterceptor wsAuthChannelInterceptor;

    @Qualifier("taskScheduler")
    private final TaskScheduler                   taskScheduler;

    @Value("${app.cors.allowed-origins:http://localhost:3000}")
    private String allowedOrigins;

    @Bean
    public static TaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("medibook-task-");
        scheduler.setDaemon(true);
        scheduler.setRemoveOnCancelPolicy(true);
        return scheduler;
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/queue", "/topic")
                .setTaskScheduler(taskScheduler)
                // 25 s heartbeat: server sends every 25 s, expects client every 25 s
                .setHeartbeatValue(new long[]{25_000, 25_000});
        registry.setApplicationDestinationPrefixes("/app");
        registry.setUserDestinationPrefix("/user");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
                .setHandshakeHandler(jwtHandshakeHandler)
                .addInterceptors(jwtHandshakeInterceptor)
                .setAllowedOriginPatterns(allowedOrigins.split(","));
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(wsAuthChannelInterceptor);
    }
}
