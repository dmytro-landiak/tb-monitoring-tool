/**
 * Copyright © 2016-2018 The Thingsboard Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.thingsboard.tools.service.device;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.netty.buffer.Unpooled;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.handler.codec.mqtt.MqttQoS;
import io.netty.util.concurrent.Future;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.RandomStringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.Netty4ClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.concurrent.ListenableFuture;
import org.springframework.util.concurrent.ListenableFutureCallback;
import org.springframework.web.client.AsyncRestTemplate;
import org.thingsboard.client.tools.RestClient;
import org.thingsboard.mqtt.MqttClient;
import org.thingsboard.mqtt.MqttClientConfig;
import org.thingsboard.mqtt.MqttConnectResult;
import org.thingsboard.server.common.data.Device;
import org.thingsboard.server.common.data.id.DeviceId;
import org.thingsboard.tools.service.email.EmailService;
import org.thingsboard.tools.service.websocket.WebSocketClientEndpoint;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Service
public class BaseDeviceAPITest implements DeviceAPITest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int CONNECT_TIMEOUT = 5;
    private static final int PUBLISHED_MESSAGES_LOG_PAUSE = 5;
    private static final int TASKS_CHECK_PAUSE = 60;
    private static final int MIN_NUMB = 0;
    private static final int MAX_NUMB = 50;

    @Autowired
    private EmailService emailService;

    @Value("${rest.url}")
    private String restUrl;

    @Value("${rest.webSocketUrl}")
    private String webSocketUrl;

    @Value("${rest.username}")
    private String username;

    @Value("${rest.password}")
    private String password;

    @Value("${mqtt.host}")
    private String mqttHost;

    @Value("${mqtt.port}")
    private int mqttPort;

    @Value("${device.count}")
    private int deviceCount;

    @Value("${performance.duration}")
    private int duration;

    @Value("${email.alertEmailsPeriod}")
    private int alertEmailsPeriod;

    @Value("${email.statusEmailPeriod}")
    private int statusEmailPeriod;

    private final ScheduledExecutorService warmUpExecutor = Executors.newScheduledThreadPool(10);
    private final ScheduledExecutorService scheduledExecutor = Executors.newScheduledThreadPool(10);
    private final ExecutorService httpExecutor = Executors.newFixedThreadPool(10);

    private final Map<Integer, TbCheckTask> subscriptionsMap = new ConcurrentHashMap<>();
    private final Map<String, MonitoringDeviceData> deviceMap = new ConcurrentHashMap<>();

    private RestClient restClient;
    private WebSocketClientEndpoint clientEndPoint;
    private EventLoopGroup eventLoopGroup;
    private AsyncRestTemplate httpClient;
    private boolean sendEmail;

    @PostConstruct
    void init() {
        restClient = new RestClient(restUrl);
        restClient.login(username, password);

        eventLoopGroup = new NioEventLoopGroup();
        Netty4ClientHttpRequestFactory nettyFactory = new Netty4ClientHttpRequestFactory(eventLoopGroup);
        httpClient = new AsyncRestTemplate(nettyFactory);

        try {
            clientEndPoint = new WebSocketClientEndpoint(new URI(webSocketUrl + "=" + restClient.getToken()));
            handleWebSocketMsg();
        } catch (URISyntaxException e) {
            log.error("Bad URI provided...", e);
        }
        scheduleAlertEmailSending();
        scheduleScriptStatusEmailSending();
        scheduleTasksCheck();
    }

    @PreDestroy
    void destroy() {
        for (MonitoringDeviceData monitoringDeviceData : deviceMap.values()) {
            monitoringDeviceData.getMqttClient().disconnect();
        }
        if (!this.httpExecutor.isShutdown()) {
            this.httpExecutor.shutdown();
        }
        if (!this.scheduledExecutor.isShutdown()) {
            this.scheduledExecutor.shutdown();
        }
        if (!this.warmUpExecutor.isShutdown()) {
            this.warmUpExecutor.shutdown();
        }
        if (this.eventLoopGroup != null) {
            this.eventLoopGroup.shutdownGracefully(0, 5, TimeUnit.SECONDS);
        }
    }

    @Override
    public void createDevices() throws Exception {
        restClient.login(username, password);
        log.info("Creating {} devices...", deviceCount);
        CountDownLatch latch = new CountDownLatch(deviceCount);
        for (int i = 0; i < deviceCount; i++) {
            httpExecutor.submit(() -> {
                Device device = null;
                try {
                    device = restClient.createDevice("Device_" + RandomStringUtils.randomNumeric(5), "default");
                    MonitoringDeviceData data = new MonitoringDeviceData();
                    data.setDeviceId(device.getId());
                    deviceMap.putIfAbsent(restClient.getCredentials(device.getId()).getCredentialsId(), data);
                } catch (Exception e) {
                    log.error("Error while creating device", e);
                    if (device != null && device.getId() != null) {
                        restClient.getRestTemplate().delete(restUrl + "/api/device/" + device.getId().getId());
                    }
                } finally {
                    latch.countDown();
                }
            });
        }
        latch.await();
        log.info("{} devices have been created successfully!", deviceMap.size());
    }

    @Override
    public void subscribeWebSockets() {
        log.info("Subscribing WebSockets for {} devices...", deviceCount);
        int cmdId = 1;
        for (Map.Entry<String, MonitoringDeviceData> entry : deviceMap.entrySet()) {
            MonitoringDeviceData data = entry.getValue();
            subscribeToWebSocket(data.getDeviceId(), cmdId);
            data.setSubscriptionId(cmdId);
            cmdId++;
        }
    }

    @Override
    public void warmUpDevices(final int publishTelemetryPause) throws InterruptedException {
        restClient.login(username, password);
        log.info("Warming up {} devices...", deviceCount);
        CountDownLatch connectLatch = new CountDownLatch(deviceCount);

        int idx = 0;
        for (Map.Entry<String, MonitoringDeviceData> entry : deviceMap.entrySet()) {
            final int delayPause = (int) ((double) publishTelemetryPause / deviceCount * idx);
            idx++;
            warmUpExecutor.schedule(() -> {
                try {
                    MqttClient client = initClient(entry.getKey());
                    MonitoringDeviceData monitoringDeviceData = entry.getValue();
                    monitoringDeviceData.setMqttClient(client);
                } catch (Exception e) {
                    log.error("Error while connect device", e);
                } finally {
                    connectLatch.countDown();
                }
            }, delayPause, TimeUnit.MILLISECONDS);
        }
        connectLatch.await();

        CountDownLatch warmUpLatch = new CountDownLatch(deviceMap.size());
        idx = 0;
        for (MonitoringDeviceData monitoringDeviceData : deviceMap.values()) {
            MqttClient mqttClient = monitoringDeviceData.getMqttClient();
            final int delayPause = (int) ((double) publishTelemetryPause / deviceMap.size() * idx);
            idx++;
            warmUpExecutor.schedule(() -> {
                mqttClient.publish("v1/devices/me/telemetry", Unpooled.wrappedBuffer(generateByteData()), MqttQoS.AT_LEAST_ONCE)
                        .addListener(future -> {
                                    if (future.isSuccess()) {
                                        log.debug("Message was successfully published to device: {}", mqttClient.getClientConfig().getUsername());
                                    } else {
                                        log.error("Error while publishing message to device: {}", mqttClient.getClientConfig().getUsername());
                                    }
                                    warmUpLatch.countDown();
                                }
                        );
            }, delayPause, TimeUnit.MILLISECONDS);
        }
        warmUpLatch.await();
        log.info("Warming up ended for {} devices...", deviceCount);
    }

    @Override
    public void runApiTests(final int publishTelemetryPause) throws InterruptedException {
        restClient.login(username, password);
        log.info("Starting TB status check test for {} devices...", deviceCount);
        AtomicInteger successPublishedCount = new AtomicInteger();
        AtomicInteger failedPublishedCount = new AtomicInteger();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        scheduledExecutor.scheduleAtFixedRate(() -> {
            try {
                log.info("[{}] messages have been published successfully. [{}] failed.",
                        successPublishedCount.get(), failedPublishedCount.get());
            } catch (Exception ignored) {
            }
        }, 0, PUBLISHED_MESSAGES_LOG_PAUSE, TimeUnit.SECONDS);

        while (true) {
            processPublishMqttMessages(publishTelemetryPause, successPublishedCount, failedPublishedCount);
            processPublishHttpMessages(publishTelemetryPause, successPublishedCount, failedPublishedCount, headers);
        }
    }

    private void subscribeToWebSocket(DeviceId deviceId, int cmdId) {
        ObjectNode objectNode = MAPPER.createObjectNode();
        objectNode.put("entityType", "DEVICE");
        objectNode.put("entityId", deviceId.toString());
        objectNode.put("scope", "LATEST_TELEMETRY");
        objectNode.put("cmdId", Integer.toString(cmdId));

        ArrayNode arrayNode = MAPPER.createArrayNode();
        arrayNode.add(objectNode);

        ObjectNode resultNode = MAPPER.createObjectNode();
        resultNode.set("tsSubCmds", arrayNode);
        try {
            clientEndPoint.sendMessage(MAPPER.writeValueAsString(resultNode));
        } catch (Exception e) {
            log.error("Failed to send the message using WebSocket", e);
        }
    }

    private MqttClient initClient(String token) throws Exception {
        MqttClientConfig config = new MqttClientConfig();
        config.setUsername(token);
        MqttClient client = MqttClient.create(config, null);
        client.setEventLoop(eventLoopGroup);
        Future<MqttConnectResult> connectFuture = client.connect(mqttHost, mqttPort);
        MqttConnectResult result;
        try {
            result = connectFuture.get(CONNECT_TIMEOUT, TimeUnit.SECONDS);
        } catch (TimeoutException ex) {
            connectFuture.cancel(true);
            client.disconnect();
            throw new RuntimeException(String.format("Failed to connect to MQTT broker at %s:%d.", mqttHost, mqttPort));
        }
        if (!result.isSuccess()) {
            connectFuture.cancel(true);
            client.disconnect();
            throw new RuntimeException(String.format("Failed to connect to MQTT broker at %s:%d. Result code is: %s", mqttHost, mqttPort, result.getReturnCode()));
        }
        return client;
    }

    private void processPublishMqttMessages(int publishTelemetryPause, AtomicInteger successPublishedCount, AtomicInteger failedPublishedCount) {
        for (Map.Entry<String, MonitoringDeviceData> entry : deviceMap.entrySet()) {
            MonitoringDeviceData monitoringDeviceData = entry.getValue();
            MqttClient mqttClient = monitoringDeviceData.getMqttClient();
            int subscriptionId = monitoringDeviceData.getSubscriptionId();
            if (subscriptionsMap.containsKey(subscriptionId)) {
                TbCheckTask task = subscriptionsMap.get(subscriptionId);
                if (task.isDone()) {
                    publishMqttMessage(successPublishedCount, failedPublishedCount, mqttClient,
                            subscriptionId);
                }
            } else {
                publishMqttMessage(successPublishedCount, failedPublishedCount, mqttClient,
                        subscriptionId);
            }
        }
        sleep(publishTelemetryPause);
    }

    private void processPublishHttpMessages(int publishTelemetryPause, AtomicInteger successPublishedCount, AtomicInteger failedPublishedCount, HttpHeaders headers) {
        for (Map.Entry<String, MonitoringDeviceData> entry : deviceMap.entrySet()) {
            String token = entry.getKey();
            String url = restUrl + "/api/v1/" + token + "/telemetry";
            HttpEntity<String> entity = new HttpEntity<>(generateStrData(), headers);
            int subscriptionId = entry.getValue().getSubscriptionId();
            if (subscriptionsMap.containsKey(subscriptionId)) {
                TbCheckTask task = subscriptionsMap.get(subscriptionId);
                if (task.isDone()) {
                    publishHttpMessage(successPublishedCount, failedPublishedCount, token,
                            url, entity, subscriptionId);
                }
            } else {
                publishHttpMessage(successPublishedCount, failedPublishedCount, token,
                        url, entity, subscriptionId);
            }
        }
        sleep(publishTelemetryPause);
    }

    private void publishMqttMessage(AtomicInteger successPublishedCount, AtomicInteger failedPublishedCount,
                                    MqttClient mqttClient, int subscriptionId) {
        subscriptionsMap.put(subscriptionId, new TbCheckTask(getCurrentTs(), false, "MQTT"));
        log.debug("Sending message via MQTT protocol...");
        try {
            mqttClient.publish("v1/devices/me/telemetry", Unpooled.wrappedBuffer(generateByteData()), MqttQoS.AT_LEAST_ONCE)
                    .addListener(future -> {
                                if (future.isSuccess()) {
                                    successPublishedCount.getAndIncrement();
                                    log.debug("Message was successfully published to device: {}", mqttClient.getClientConfig().getUsername());
                                } else {
                                    failedPublishedCount.getAndIncrement();
                                    log.error("Error while publishing message to device: {}", mqttClient.getClientConfig().getUsername());
                                }
                            }
                    );
        } catch (Exception e) {
            failedPublishedCount.getAndIncrement();
        }
    }

    private void publishHttpMessage(AtomicInteger successPublishedCount, AtomicInteger failedPublishedCount,
                                    String token, String url, HttpEntity<String> entity, int subscriptionId) {
        log.debug("Sending message via HTTP protocol...");
        subscriptionsMap.put(subscriptionId, new TbCheckTask(getCurrentTs(), false, "HTTP"));

        ListenableFuture<ResponseEntity<Void>> future = httpClient.exchange(url, HttpMethod.POST, entity, Void.class);
        future.addCallback(new ListenableFutureCallback<ResponseEntity>() {
            @Override
            public void onFailure(Throwable throwable) {
                failedPublishedCount.getAndIncrement();
                log.error("Error while publishing telemetry, token: {}", token, throwable);
            }

            @Override
            public void onSuccess(ResponseEntity responseEntity) {
                if (responseEntity.getStatusCode().is2xxSuccessful()) {
                    successPublishedCount.getAndIncrement();
                } else {
                    failedPublishedCount.getAndIncrement();
                    log.error("Error while publishing telemetry, token: {}, status code: {}", token, responseEntity.getStatusCode().getReasonPhrase());
                }
            }
        });
    }

    private byte[] generateByteData() {
        return generateStrData().getBytes(StandardCharsets.UTF_8);
    }

    private String generateStrData() {
        ObjectNode node = MAPPER.createObjectNode().put("temperature", getRandomValue(MIN_NUMB, MAX_NUMB));
        try {
            return MAPPER.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            log.warn("Failed to write JSON as String [{}]", node, e);
        }
        return "{\"temperature\":30}";
    }

    private int getRandomValue(int min, int max) {
        return (int) (Math.random() * ((max - min) + 1)) + min;
    }

    private long getCurrentTs() {
        return System.currentTimeMillis();
    }

    private void sleep(int publishTelemetryPause) {
        try {
            Thread.sleep(publishTelemetryPause);
        } catch (Exception ignored) {
        }
    }

    private void handleWebSocketMsg() {
        clientEndPoint.addMessageHandler(message -> {
            log.info("Arrived message via WebSocket: {}", message);
            try {
                int subscriptionId = MAPPER.readTree(message).get("subscriptionId").asInt();
                if (subscriptionsMap.containsKey(subscriptionId)) {
                    TbCheckTask task = subscriptionsMap.get(subscriptionId);
                    task.setDone(true);
                    sendEmail = getCurrentTs() - task.getStartTs() > duration;
                }
            } catch (IOException e) {
                log.warn("Failed to read message to json {}", message, e);
            }
        });
    }

    private void scheduleAlertEmailSending() {
        scheduledExecutor.scheduleAtFixedRate(() -> {
            try {
                if (sendEmail) {
                    log.info("Sending an email in case of any troubles with the TB!");
                    emailService.sendAlertEmail();
                    sendEmail = false;
                }
            } catch (Exception ignored) {
            }
        }, 3, alertEmailsPeriod, TimeUnit.MINUTES);
    }

    private void scheduleScriptStatusEmailSending() {
        scheduledExecutor.scheduleAtFixedRate(() -> {
            try {
                log.info("Sending an email that the monitoring script is running fine!");
                emailService.sendStatusEmail();
            } catch (Exception ignored) {
            }
        }, 5, statusEmailPeriod, TimeUnit.MINUTES);
    }

    private void scheduleTasksCheck() {
        scheduledExecutor.scheduleAtFixedRate(() -> {
            for (Map.Entry<Integer, TbCheckTask> entry : subscriptionsMap.entrySet()) {
                TbCheckTask task = entry.getValue();
                if (!task.isDone() && getCurrentTs() - task.getStartTs() > duration) {
                    task.setDone(true);
                    sendEmail = true;
                }
            }
        }, 0, TASKS_CHECK_PAUSE, TimeUnit.SECONDS);
    }

}
