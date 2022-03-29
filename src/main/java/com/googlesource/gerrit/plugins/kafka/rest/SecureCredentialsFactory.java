// Copyright (C) 2011 The Android Open Source Project
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

package com.googlesource.gerrit.plugins.kafka.rest;

import com.google.common.base.Strings;
import com.google.inject.Inject;
import com.googlesource.gerrit.plugins.kafka.config.KafkaProperties;
import java.net.URISyntaxException;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.nio.client.HttpAsyncClientBuilder;

/** Looks up a remote's password in secure.config. */
public class SecureCredentialsFactory {
  private final KafkaProperties config;

  @Inject
  public SecureCredentialsFactory(KafkaProperties config) {
    this.config = config;
  }

  public HttpAsyncClientBuilder apply(HttpAsyncClientBuilder custom) throws URISyntaxException {
    String user = config.getRestApiUsername();
    String pass = config.getRestApiPassword();
    if (!Strings.isNullOrEmpty(user) && !Strings.isNullOrEmpty(pass)) {
      CredentialsProvider credsProvider = new BasicCredentialsProvider();
      credsProvider.setCredentials(
          new AuthScope(config.getRestApiUri().getHost(), config.getRestApiUri().getPort()),
          new UsernamePasswordCredentials(user, pass));
      custom.setDefaultCredentialsProvider(credsProvider);
    }
    return custom;
  }
}
