// Copyright (C) 2023 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.googlesource.gerrit.plugins.kafka.session;

import com.google.gerrit.extensions.systemstatus.ServerInformation;
import com.google.gerrit.server.util.PluginLogFile;
import com.google.gerrit.server.util.SystemLog;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.googlesource.gerrit.plugins.kafka.Manager;
import com.googlesource.gerrit.plugins.kafka.config.KafkaProperties;
import com.googlesource.gerrit.plugins.kafka.rest.KafkaRestClient;
import org.apache.log4j.PatternLayout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Singleton
public class Log4JKafkaMessageLogger extends PluginLogFile {
  private final Logger msgLog;
  private final KafkaProperties configuration;

  @Inject
  public Log4JKafkaMessageLogger(
      SystemLog systemLog, ServerInformation serverInfo, KafkaProperties configuration) {
    super(
        systemLog,
        serverInfo,
        Manager.KAFKA_LOG_NAME,
        new PatternLayout("[%d{ISO8601}] [%t] %-5p : %m%n"));
    this.msgLog = LoggerFactory.getLogger(Manager.KAFKA_LOG_NAME);
    this.configuration = configuration;
  }

  @Override
  public void start() {
    super.start();
    if (configuration.isHttpWireLog()) {
      KafkaRestClient.enableHttpWireLog();
    }
  }

  public void log(String topic, String event) {
    msgLog.info("PUBLISH {} {}", topic, event);
  }
}
