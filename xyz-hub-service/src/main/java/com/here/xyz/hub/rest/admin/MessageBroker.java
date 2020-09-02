/*
 * Copyright (C) 2017-2019 HERE Europe B.V.
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
 *
 * SPDX-License-Identifier: Apache-2.0
 * License-Filename: LICENSE
 */

package com.here.xyz.hub.rest.admin;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.here.xyz.hub.Service;
import com.here.xyz.hub.rest.AdminApi;
import com.here.xyz.hub.rest.admin.messages.RelayedMessage;
import com.here.xyz.hub.rest.admin.messages.brokers.RedisMessageBroker;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.List;

import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * The MessageBroker provides the infrastructural implementation of how to send & receive {@link AdminMessage}s.
 *
 * NOTE: The {@link MessageBroker#getInstance()} method decides which implementation to return as the default implementation.
 */
public interface MessageBroker {

  Logger logger = LogManager.getLogger();
  ThreadLocal<ObjectMapper> mapper = ThreadLocal.withInitial(ObjectMapper::new);

  List<String> hubRemoteUrls = Arrays.asList(Service.configuration.XYZ_HUB_REMOTE_SERVICE_URLS.split(";"));

  void sendRawMessage(String jsonMessage);

  default void sendMessage(AdminMessage message) {
    if (!Node.OWN_INSTANCE.equals(message.destination)) {
      String jsonMessage = null;
      try {
        jsonMessage = mapper.get().writeValueAsString(message);
        sendRawMessage(jsonMessage);

        if (message instanceof RelayedMessage) {
          RelayedMessage rm = (RelayedMessage) message;
          if (rm.globalRelay) {

            //Remote message does not need to be globally relayed again.
            rm.globalRelay = false;

            rm.relay = true;
            rm.broadcastIncludeLocalNode = true;
            //Send messages to remote cluster async.
            Service.vertx.executeBlocking(future -> {
              sendRawMessagesToRemoteCluster(rm, future);
            }, ar -> {
              if (ar.failed())
                logger.error(ar.cause());
            });
          }
        }
      }
      catch (JsonProcessingException e) {
        logger.error("Error while serializing AdminMessage of type {} prior to send it.", message.getClass().getSimpleName());
      }
      catch (Exception e) {
        logger.error("Error while sending AdminMessage: {}", jsonMessage);
      }
    }
    //Receive it (also) locally (if applicable)
    /*
    NOTE: Local messages will always be received directly and only once. This is also true for a broadcast message
    with the #broadcastIncludeLocalNode flag being active.
     */
    receiveMessage(message);
  }

  default void sendRawMessagesToRemoteCluster(RelayedMessage message, Future<Object> future) {
    if (hubRemoteUrls != null && !hubRemoteUrls.isEmpty()) {

      for (String remoteUrl : hubRemoteUrls) {
        int tryCount = 0;
        boolean retry = false;
        do {
          tryCount++;
          try {
            byte[] body = mapper.get().writeValueAsBytes(message);
            synchronized (Service.webClient) {
              Service.webClient
                      .postAbs(remoteUrl + AdminApi.ADMIN_MESSAGES_ENDPOINT)
                      .timeout(Service.configuration.REMOTE_FUNCTION_REQUEST_TIMEOUT)
                      .putHeader("content-type", "application/json; charset=" + Charset.defaultCharset().name())
                      .putHeader("Authorization", "Bearer " + Service.configuration.ADMIN_MESSAGE_JWT)
                      .sendBuffer(Buffer.buffer(body), ar -> {
                        if (ar.failed()) {
                          future.fail("Failed to sent message to remote cluster at " + hubRemoteUrls + ": " + ar.cause());
                        } else {
                          future.complete();
                        }
                      });
            }
          } catch (JsonProcessingException e) {
            future.fail("Error while serializing RelayedMessage prior to send it to remote cluster. URL: " + hubRemoteUrls + " Error: " + e.getMessage());
          } catch (Exception e) {
            if (!retry) {
              logger.error("Error sending event to remote http service. Retrying once...", e);
              retry = true;
            } else {
              future.fail("Error sending event to remote http service twice. " + e.getMessage());
            }
          }
        } while (retry && tryCount <= 1);
      }
    }
  }

  default void receiveRawMessage(byte[] rawJsonMessage) {
    if (rawJsonMessage == null) {
      logger.error("No bytes given for receiving the message.", new NullPointerException());
      return;
    }
    receiveRawMessage(new String(rawJsonMessage));
  }

  default void receiveRawMessage(String jsonMessage) {
    receiveMessage(deserializeMessage(jsonMessage));
  }

  default AdminMessage deserializeMessage(String jsonMessage) {
    AdminMessage message = null;
    try {
      message = mapper.get().readValue(jsonMessage, AdminMessage.class);
    }
    catch (IOException e) {
      logger.error("Error while de-serializing AdminMessage {} : {}", jsonMessage, e);
    }
    catch (Exception e) {
      logger.error("Error while receiving AdminMessage {} : {}", jsonMessage, e);
    }
    return message;
  }

  default void receiveMessage(AdminMessage message) {
    if (message == null)
      return;
    if (message.source == null)
      throw new NullPointerException("The source node of the AdminMessage must be defined.");

    if (message.destination == null && (!Node.OWN_INSTANCE.equals(message.source) || message.broadcastIncludeLocalNode)
        || Node.OWN_INSTANCE.equals(message.destination)) {
      try {
        message.handle();
      }
      catch (RuntimeException e) {
        logger.error("Error while trying to handle AdminMessage {} : {}", message, e);
      }
    }
  }

  static MessageBroker getInstance() {
    //Return an instance of the default implementation
    return RedisMessageBroker.getInstance();
  }

}
