/*
 * Copyright (C) 2013 Open WhisperSystems
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.whispersystems.textsecuregcm.push;

import com.codahale.metrics.Gauge;
import com.codahale.metrics.SharedMetricRegistries;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.whispersystems.textsecuregcm.push.ApnFallbackManager.ApnFallbackTask;
import org.whispersystems.textsecuregcm.push.WebsocketSender.DeliveryStatus;
import org.whispersystems.textsecuregcm.storage.Account;
import org.whispersystems.textsecuregcm.storage.Device;
import org.whispersystems.textsecuregcm.util.BlockingThreadPoolExecutor;
import org.whispersystems.textsecuregcm.util.Constants;
import org.whispersystems.textsecuregcm.util.Util;
import org.whispersystems.textsecuregcm.websocket.WebsocketAddress;

import java.util.concurrent.TimeUnit;

import static com.codahale.metrics.MetricRegistry.name;
import io.dropwizard.lifecycle.Managed;
import static org.whispersystems.textsecuregcm.entities.MessageProtos.Envelope;


public class PushSender implements Managed {

  private final Logger logger = LoggerFactory.getLogger(PushSender.class);

  //private static final String APN_PAYLOAD = "{\"aps\":{\"content-available\":1}, \"type\": \"signal_message\", \"unread\": %d}";
  private static final String APN_PAYLOAD = "{\"aps\":{\"content-available\":1,\"sound\":\"default\",\"badge\":%d,\"alert\":{\"loc-key\":\"APN_Message\"}}}";

  private final ApnFallbackManager         apnFallbackManager;
  private final GCMSender                  gcmSender;
  private final APNSender                  apnSender;
  private final WebsocketSender            webSocketSender;
  private final BlockingThreadPoolExecutor executor;
  private final int                        queueSize;

  public PushSender(ApnFallbackManager apnFallbackManager,
                    GCMSender gcmSender, APNSender apnSender,
                    WebsocketSender websocketSender, int queueSize)
  {
    this.apnFallbackManager = apnFallbackManager;
    this.gcmSender          = gcmSender;
    this.apnSender          = apnSender;
    this.webSocketSender    = websocketSender;
    this.queueSize          = queueSize;
    this.executor           = new BlockingThreadPoolExecutor(50, queueSize);

    SharedMetricRegistries.getOrCreate(Constants.METRICS_NAME)
                          .register(name(PushSender.class, "send_queue_depth"),
                                    new Gauge<Integer>() {
                                      @Override
                                      public Integer getValue() {
                                        return executor.getSize();
                                      }
                                    });
  }

  public void sendMessage(final Account account, final Device device, final Envelope message, final boolean silent)
      throws NotPushRegisteredException
  {
    sendMessage(account, device, message, silent, null);
  }

  public void sendMessage(final Account account, final Device device, final Envelope message, final boolean silent, final String messageTag)
      throws NotPushRegisteredException
  {
    logger.info("               PUSH SENDER SEND MESSAGE               ");

    if (device.getGcmId() == null && device.getApnId() == null && !device.getFetchesMessages()) {
      throw new NotPushRegisteredException("No delivery possible!");
    }

    if (queueSize > 0) {
      executor.execute(new Runnable() {
        @Override
        public void run() {
          sendSynchronousMessage(account, device, message, silent, messageTag);
        }
      });
    } else {
      sendSynchronousMessage(account, device, message, silent, messageTag);
    }
  }

  public void sendQueuedNotification(Account account, Device device, int messageQueueDepth, boolean fallback)
      throws NotPushRegisteredException, TransientPushFailureException
  {
    sendQueuedNotification(account, device, messageQueueDepth, fallback, null);
  }

  public void sendQueuedNotification(Account account, Device device, int messageQueueDepth, boolean fallback, String source)
      throws NotPushRegisteredException, TransientPushFailureException
  {
    if      (device.getGcmId() != null)    sendGcmNotification(account, device, source);
    else if (device.getApnId() != null)    sendApnNotification(account, device, messageQueueDepth, fallback);
    else if (!device.getFetchesMessages()) throw new NotPushRegisteredException("No notification possible!");
  }

  public WebsocketSender getWebSocketSender() {
    return webSocketSender;
  }

  private void sendSynchronousMessage(Account account, Device device, Envelope message, boolean silent) {
    sendSynchronousMessage(account, device, message, silent, null);
  }

  private void sendSynchronousMessage(Account account, Device device, Envelope message, boolean silent, String messageTag) {
    if      (device.getGcmId() != null)   sendGcmMessage(account, device, message, messageTag, silent);
    else if (device.getApnId() != null)   sendApnMessage(account, device, message, silent);
    else if (device.getFetchesMessages()) sendWebSocketMessage(account, device, message, messageTag);
    else                                  throw new AssertionError();
  }

  private void sendGcmMessage(Account account, Device device, Envelope message) {
    sendGcmMessage(account, device, message, null, false);
  }

  private void sendGcmMessage(Account account, Device device, Envelope message, String messageTag, boolean silent) {
    logger.info("PUSH SENDER SEND GCM MESSAGE | silent: " + silent + ", type: " + message.getType() + ", messageTag: " + messageTag);
    if (message.getType() == Envelope.Type.RECEIPT) return; // force to not send receipt notifications

    DeliveryStatus deliveryStatus = webSocketSender.sendMessage(account, device, message, WebsocketSender.Type.GCM, messageTag);

    if (silent) return;
    logger.info("PUSH SENDER SEND GCM MESSAGE | deliveryStatus: " + deliveryStatus.isDelivered());
    if (!deliveryStatus.isDelivered()) {
      sendGcmNotification(account, device, message.getSource());
    }
  }

  private void sendGcmNotification(Account account, Device device) {
    sendGcmNotification(account, device, null);
  }

  private void sendGcmNotification(Account account, Device device, String source) {
    GcmMessage gcmMessage = new GcmMessage(device.getGcmId(), account.getNumber(),
                                           (int)device.getId(), false);

    gcmSender.sendMessage(gcmMessage, source);
  }

  private void sendApnMessage(Account account, Device device, Envelope outgoingMessage, boolean silent) {
    logger.info("Sending APN Message to: "+account.getNumber());
    DeliveryStatus deliveryStatus = webSocketSender.sendMessage(account, device, outgoingMessage, WebsocketSender.Type.APN);

    if (!deliveryStatus.isDelivered() && outgoingMessage.getType() != Envelope.Type.RECEIPT) {
      logger.info("Sending APN push notification, because websocket delivery failed");
      boolean fallback = !silent && !outgoingMessage.getSource().equals(account.getNumber());
      sendApnNotification(account, device, deliveryStatus.getMessageQueueDepth(), fallback);
    } else {
      logger.info("Not sending APN push notification, because websocket delivery succeeded");
    }
  }

  private void sendApnNotification(Account account, Device device, int messageQueueDepth, boolean fallback) {
    ApnMessage apnMessage;

    //because there are no VOIP features enabled in iOS client
    //we are not permitted to use VOIP PNs
    if (false) {// !Util.isEmpty(device.getVoipApnId())) {
      logger.info("Using VOIP APN");
      apnMessage = new ApnMessage(device.getVoipApnId(), account.getNumber(), (int)device.getId(),
                                  String.format(APN_PAYLOAD, messageQueueDepth), true,
                                  System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(ApnFallbackManager.FALLBACK_DURATION));

      if (fallback) {
        apnFallbackManager.schedule(new WebsocketAddress(account.getNumber(), device.getId()),
                                    new ApnFallbackTask(device.getApnId(), device.getVoipApnId(), apnMessage));
      }
    } else {
      logger.info("Using non-VOIP APN");
      apnMessage = new ApnMessage(device.getApnId(), account.getNumber(), (int)device.getId(),
                                  String.format(APN_PAYLOAD, messageQueueDepth),
                                  false, ApnMessage.MAX_EXPIRATION);
    }

    try {
      apnSender.sendMessage(apnMessage);
    } catch (TransientPushFailureException e) {
      logger.warn("SILENT PUSH LOSS", e);
    }
  }

  private void sendWebSocketMessage(Account account, Device device, Envelope outgoingMessage, String messageTag)
  {
    webSocketSender.sendMessage(account, device, outgoingMessage, WebsocketSender.Type.WEB, messageTag);
  }

  @Override
  public void start() throws Exception {
    apnSender.start();
    gcmSender.start();
  }

  @Override
  public void stop() throws Exception {
    executor.shutdown();
    executor.awaitTermination(5, TimeUnit.MINUTES);

    apnSender.stop();
    gcmSender.stop();
  }
}
