// Copyright (C) 2021 The Android Open Source Project
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

package com.googlesource.gerrit.plugins.kafka.config;

import com.google.gerrit.httpd.ProxyProperties;
import com.google.inject.Inject;
import com.google.inject.Provider;
import java.net.URL;
import java.util.Optional;
import org.apache.http.HttpHost;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.config.RequestConfig.Builder;

public class RequestConfigProvider implements Provider<RequestConfig> {

  private final Optional<HttpHost> proxyHost;

  @Inject
  public RequestConfigProvider(ProxyProperties proxyConf) {
    proxyHost =
        Optional.ofNullable(proxyConf.getProxyUrl()).map(URL::toString).map(HttpHost::create);
  }

  @Override
  public RequestConfig get() {
    Builder configBuilder = RequestConfig.custom();
    configBuilder = proxyHost.map(configBuilder::setProxy).orElse(configBuilder);
    return configBuilder.build();
  }
}
