/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.solr.crossdc.common;

import org.apache.solr.common.SolrException;
import org.apache.solr.common.cloud.CollectionProperties;
import org.apache.solr.common.cloud.SolrZkClient;
import org.apache.solr.crossdc.common.ConfigProperty;
import org.apache.solr.crossdc.common.CrossDcConf;
import org.apache.solr.crossdc.common.KafkaCrossDcConf;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

import static org.apache.solr.crossdc.common.KafkaCrossDcConf.BOOTSTRAP_SERVERS;
import static org.apache.solr.crossdc.common.KafkaCrossDcConf.TOPIC_NAME;

public class ConfUtil {
  private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  public static void fillProperties(SolrZkClient solrClient, Map<String, Object> properties) {
    // fill in from environment
    Map<String, String> env = System.getenv();
    for (ConfigProperty configKey : KafkaCrossDcConf.CONFIG_PROPERTIES) {
      String val = env.get(configKey.getKey());
      if (val == null) {
        // try upper-case
        val = env.get(configKey.getKey().toUpperCase(Locale.ROOT));
      }
      if (val != null) {
        properties.put(configKey.getKey(), val);
      }
    }
    // fill in from system properties
    for (ConfigProperty configKey : KafkaCrossDcConf.CONFIG_PROPERTIES) {
      String val = System.getProperty(configKey.getKey());
      if (val != null) {
        properties.put(configKey.getKey(), val);
      }
    }
    Properties zkProps = new Properties();
    if (solrClient != null) {
      try {
        if (solrClient.exists(System.getProperty(CrossDcConf.ZK_CROSSDC_PROPS_PATH,
            CrossDcConf.CROSSDC_PROPERTIES), true)) {
          byte[] data = solrClient.getData(System.getProperty(CrossDcConf.ZK_CROSSDC_PROPS_PATH,
              CrossDcConf.CROSSDC_PROPERTIES), null, null, true);

          if (data == null) {
            log.error(CrossDcConf.CROSSDC_PROPERTIES + " file in Zookeeper has no data");
            throw new SolrException(SolrException.ErrorCode.SERVER_ERROR, CrossDcConf.CROSSDC_PROPERTIES
                + " file in Zookeeper has no data");
          }

          zkProps.load(new ByteArrayInputStream(data));

          KafkaCrossDcConf.readZkProps(properties, zkProps);
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        log.error("Interrupted looking for CrossDC configuration in Zookeeper", e);
        throw new SolrException(SolrException.ErrorCode.SERVICE_UNAVAILABLE, e);
      } catch (Exception e) {
        log.error("Exception looking for CrossDC configuration in Zookeeper", e);
        throw new SolrException(SolrException.ErrorCode.SERVER_ERROR, "Exception looking for CrossDC configuration in Zookeeper", e);
      }
    }
  }

  public static void fillCollectionProperties(Map<String, String> collectionProperties, Map<String, Object> properties) throws IOException {
    for (ConfigProperty configKey : KafkaCrossDcConf.CONFIG_PROPERTIES) {
      String val = collectionProperties.get("crossdc." + configKey.getKey());
      if (val != null && !val.isBlank()) {
        properties.put(configKey.getKey(), val);
      }
    }
  }

  public static void processFileValueReferences(Map<String, Object> properties) {
    for (Map.Entry<String, Object> entry : properties.entrySet()) {
      if (entry.getValue() == null) {
        continue;
      }
      String val = entry.getValue().toString();
      if (val.startsWith("@file:")) {
        String filePath = val.substring(6); // Skip "@file:"
        try {
          val = new String(Files.readAllBytes(Paths.get(filePath)), StandardCharsets.UTF_8);
        } catch (IOException e) {
          throw new SolrException(SolrException.ErrorCode.SERVER_ERROR, "Error reading file for property " + entry.getKey(), e);
        }
        entry.setValue(val);
      }
    }
  }

  public static void verifyProperties(Map<String, Object> properties) {
    if (properties.get(BOOTSTRAP_SERVERS) == null) {
      log.error(
          "bootstrapServers not specified for producer in CrossDC configuration props={}",
          properties);
      throw new SolrException(SolrException.ErrorCode.SERVER_ERROR, "bootstrapServers not specified in configuration");
    }

    if (properties.get(TOPIC_NAME) == null) {
      log.error(
          "topicName not specified for producer in CrossDC configuration props={}",
          properties);
      throw new SolrException(SolrException.ErrorCode.SERVER_ERROR, "topicName not specified in configuration");
    }
  }
}
