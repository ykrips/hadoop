/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.yarn.client.api.impl;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.UndeclaredThrowableException;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLConnection;
import java.security.GeneralSecurityException;
import java.security.PrivilegedExceptionAction;
import java.util.Arrays;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSocketFactory;
import javax.ws.rs.core.MediaType;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.GnuParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Options;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.classification.InterfaceAudience.Private;
import org.apache.hadoop.classification.InterfaceStability.Unstable;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hadoop.security.authentication.client.ConnectionConfigurator;
import org.apache.hadoop.security.ssl.SSLFactory;
import org.apache.hadoop.security.token.Token;
import org.apache.hadoop.security.token.delegation.web.DelegationTokenAuthenticatedURL;
import org.apache.hadoop.security.token.delegation.web.DelegationTokenAuthenticator;
import org.apache.hadoop.security.token.delegation.web.KerberosDelegationTokenAuthenticator;
import org.apache.hadoop.security.token.delegation.web.PseudoDelegationTokenAuthenticator;
import org.apache.hadoop.yarn.api.records.timeline.TimelineDomain;
import org.apache.hadoop.yarn.api.records.timeline.TimelineDomains;
import org.apache.hadoop.yarn.api.records.timeline.TimelineEntities;
import org.apache.hadoop.yarn.api.records.timeline.TimelineEntity;
import org.apache.hadoop.yarn.api.records.timeline.TimelinePutResponse;
import org.apache.hadoop.yarn.client.api.TimelineClient;
import org.apache.hadoop.yarn.conf.YarnConfiguration;
import org.apache.hadoop.yarn.exceptions.YarnException;
import org.apache.hadoop.yarn.exceptions.YarnRuntimeException;
import org.apache.hadoop.yarn.security.client.TimelineDelegationTokenIdentifier;
import org.apache.hadoop.yarn.webapp.YarnJacksonJaxbJsonProvider;
import org.codehaus.jackson.map.ObjectMapper;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.sun.jersey.api.client.Client;
import com.sun.jersey.api.client.filter.ClientFilter;
import com.sun.jersey.api.client.ClientResponse;
import com.sun.jersey.api.client.ClientRequest;
import com.sun.jersey.api.client.ClientHandlerException;
import com.sun.jersey.api.client.WebResource;
import com.sun.jersey.api.client.config.ClientConfig;
import com.sun.jersey.api.client.config.DefaultClientConfig;
import com.sun.jersey.client.urlconnection.HttpURLConnectionFactory;
import com.sun.jersey.client.urlconnection.URLConnectionClientHandler;

@Private
@Unstable
public class TimelineClientImpl extends TimelineClient {

  private static final Log LOG = LogFactory.getLog(TimelineClientImpl.class);
  private static final String RESOURCE_URI_STR = "/ws/v1/timeline/";
  private static final Joiner JOINER = Joiner.on("");
  public final static int DEFAULT_SOCKET_TIMEOUT = 1 * 60 * 1000; // 1 minute

  private static Options opts;
  private static final String ENTITY_DATA_TYPE = "entity";
  private static final String DOMAIN_DATA_TYPE = "domain";

  static {
    opts = new Options();
    opts.addOption("put", true, "Put the timeline entities/domain in a JSON file");
    opts.getOption("put").setArgName("Path to the JSON file");
    opts.addOption(ENTITY_DATA_TYPE, false, "Specify the JSON file contains the entities");
    opts.addOption(DOMAIN_DATA_TYPE, false, "Specify the JSON file contains the domain");
    opts.addOption("help", false, "Print usage");
  }

  private Client client;
  private ConnectionConfigurator connConfigurator;
  private DelegationTokenAuthenticator authenticator;
  private DelegationTokenAuthenticatedURL.Token token;
  private URI resURI;
  private boolean isEnabled;

  private TimelineJerseyRetryFilter retryFilter;

  static class TimelineJerseyRetryFilter extends ClientFilter {
    // maxRetries < 0 means keep trying
    @Private
    @VisibleForTesting
    public int maxRetries;

    @Private
    @VisibleForTesting
    public long retryInterval;

    // Indicates if retries happened last time
    @Private
    @VisibleForTesting
    public boolean retried = false;

    // Constructor with default retry settings
    public TimelineJerseyRetryFilter(Configuration conf) {
      super();
      maxRetries = conf.getInt(
        YarnConfiguration.TIMELINE_SERVICE_CLIENT_MAX_RETRIES,
        YarnConfiguration.DEFAULT_TIMELINE_SERVICE_CLIENT_MAX_RETRIES);
      retryInterval = conf.getLong(
        YarnConfiguration.TIMELINE_SERVICE_CLIENT_RETRY_INTERVAL_MS,
        YarnConfiguration.DEFAULT_TIMELINE_SERVICE_CLIENT_RETRY_INTERVAL_MS);
    }

    @Override
    public ClientResponse handle(ClientRequest cr)
        throws ClientHandlerException {
      int leftRetries = maxRetries;
      retried = false;
      // keep trying
      while (true) {
        try {
          // try pass the request on, if fail, keep retrying
          return getNext().handle(cr);
        } catch (ClientHandlerException e) {
          // break if there's no retries left
          if (leftRetries == 0) {
            break;
          }
          if(e.getCause() instanceof ConnectException) {
            if (leftRetries > 0) {
              LOG.info("Connection Timeout (" + cr.getURI() + "), will try "
                  + leftRetries + " more time(s).");
            } else {
              // note that maxRetries may be -1 at the very beginning
              // maxRetries = -1 means keep trying
              LOG.info("Connection Timeout (" + cr.getURI()
                  + "), will keep retrying.");
            }
            retried = true;
          } else {
            throw e;
          }
        }
        if (leftRetries > 0) {
          leftRetries--;
        }
        try {
          // sleep for the given time interval
          Thread.sleep(retryInterval);
        } catch (InterruptedException ie) {
          LOG.warn("Client retry sleep interrupted! ");
        }
      }
      throw new ClientHandlerException("Failed to connect to timeline server. "
          + "Connection retries limit exceeded. "
          + "The posted timeline event may be missing");
    };
  }

  public TimelineClientImpl() {
    super(TimelineClientImpl.class.getName());
  }

  protected void serviceInit(Configuration conf) throws Exception {
    isEnabled = conf.getBoolean(
        YarnConfiguration.TIMELINE_SERVICE_ENABLED,
        YarnConfiguration.DEFAULT_TIMELINE_SERVICE_ENABLED);
    if (!isEnabled) {
      LOG.info("Timeline service is not enabled");
    } else {
      ClientConfig cc = new DefaultClientConfig();
      cc.getClasses().add(YarnJacksonJaxbJsonProvider.class);
      connConfigurator = newConnConfigurator(conf);
      if (UserGroupInformation.isSecurityEnabled()) {
        authenticator = new KerberosDelegationTokenAuthenticator();
      } else {
        authenticator = new PseudoDelegationTokenAuthenticator();
      }
      authenticator.setConnectionConfigurator(connConfigurator);
      client = new Client(new URLConnectionClientHandler(
          new TimelineURLConnectionFactory()), cc);
      token = new DelegationTokenAuthenticatedURL.Token();
      retryFilter = new TimelineJerseyRetryFilter(conf);
      client.addFilter(retryFilter);

      if (YarnConfiguration.useHttps(conf)) {
        resURI = URI
            .create(JOINER.join("https://", conf.get(
                YarnConfiguration.TIMELINE_SERVICE_WEBAPP_HTTPS_ADDRESS,
                YarnConfiguration.DEFAULT_TIMELINE_SERVICE_WEBAPP_HTTPS_ADDRESS),
                RESOURCE_URI_STR));
      } else {
        resURI = URI.create(JOINER.join("http://", conf.get(
            YarnConfiguration.TIMELINE_SERVICE_WEBAPP_ADDRESS,
            YarnConfiguration.DEFAULT_TIMELINE_SERVICE_WEBAPP_ADDRESS),
            RESOURCE_URI_STR));
      }
      LOG.info("Timeline service address: " + resURI);
    }
    super.serviceInit(conf);
  }

  @Override
  public TimelinePutResponse putEntities(
      TimelineEntity... entities) throws IOException, YarnException {
    if (!isEnabled) {
      if (LOG.isDebugEnabled()) {
        LOG.debug("Nothing will be put because timeline service is not enabled");
      }
      return new TimelinePutResponse();
    }
    TimelineEntities entitiesContainer = new TimelineEntities();
    entitiesContainer.addEntities(Arrays.asList(entities));
    ClientResponse resp = doPosting(entitiesContainer, null);
    return resp.getEntity(TimelinePutResponse.class);
  }


  @Override
  public void putDomain(TimelineDomain domain) throws IOException,
      YarnException {
    if (!isEnabled) {
      if (LOG.isDebugEnabled()) {
        LOG.debug("Nothing will be put because timeline service is not enabled");
      }
      return;
    }
    doPosting(domain, "domain");
  }

  private ClientResponse doPosting(Object obj, String path) throws IOException, YarnException {
    ClientResponse resp;
    try {
      resp = doPostingObject(obj, path);
    } catch (RuntimeException re) {
      // runtime exception is expected if the client cannot connect the server
      String msg =
          "Failed to get the response from the timeline server.";
      LOG.error(msg, re);
      throw re;
    }
    if (resp == null ||
        resp.getClientResponseStatus() != ClientResponse.Status.OK) {
      String msg =
          "Failed to get the response from the timeline server.";
      LOG.error(msg);
      if (LOG.isDebugEnabled() && resp != null) {
        String output = resp.getEntity(String.class);
        LOG.debug("HTTP error code: " + resp.getStatus()
            + " Server response : \n" + output);
      }
      throw new YarnException(msg);
    }
    return resp;
  }

  @SuppressWarnings("unchecked")
  @Override
  public Token<TimelineDelegationTokenIdentifier> getDelegationToken(
      final String renewer) throws IOException, YarnException {
    boolean isProxyAccess =
        UserGroupInformation.getCurrentUser().getAuthenticationMethod()
        == UserGroupInformation.AuthenticationMethod.PROXY;
    UserGroupInformation callerUGI = isProxyAccess ?
        UserGroupInformation.getCurrentUser().getRealUser()
        : UserGroupInformation.getCurrentUser();
    final String doAsUser = isProxyAccess ?
        UserGroupInformation.getCurrentUser().getShortUserName() : null;
    try {
      return callerUGI.doAs(
          new PrivilegedExceptionAction<Token<TimelineDelegationTokenIdentifier>>() {
        @Override
        public Token<TimelineDelegationTokenIdentifier> run() throws Exception {
          DelegationTokenAuthenticatedURL authUrl =
              new DelegationTokenAuthenticatedURL(authenticator, connConfigurator);
          return (Token) authUrl.getDelegationToken(
              resURI.toURL(), token, renewer, doAsUser);
        }
      });
    } catch (UndeclaredThrowableException e) {
      throw new IOException(e.getCause());
    } catch (InterruptedException e) {
      throw new IOException(e);
    }
  }

  @Private
  @VisibleForTesting
  public TimelineJerseyRetryFilter getRetryFilter() {
    return retryFilter;
  }

  @Private
  @VisibleForTesting
  public ClientResponse doPostingObject(Object object, String path) {
    WebResource webResource = client.resource(resURI);
    if (path == null) {
      return webResource.accept(MediaType.APPLICATION_JSON)
          .type(MediaType.APPLICATION_JSON)
          .post(ClientResponse.class, object);
    } else if (path.equals("domain")) {
      return webResource.path(path).accept(MediaType.APPLICATION_JSON)
          .type(MediaType.APPLICATION_JSON)
          .put(ClientResponse.class, object);
    } else {
      throw new YarnRuntimeException("Unknown resource type");
    }
  }

  private class TimelineURLConnectionFactory
      implements HttpURLConnectionFactory {

    @Override
    public HttpURLConnection getHttpURLConnection(final URL url) throws IOException {
      boolean isProxyAccess =
          UserGroupInformation.getCurrentUser().getAuthenticationMethod()
          == UserGroupInformation.AuthenticationMethod.PROXY;
      UserGroupInformation callerUGI = isProxyAccess ?
          UserGroupInformation.getCurrentUser().getRealUser()
          : UserGroupInformation.getCurrentUser();
      final String doAsUser = isProxyAccess ?
          UserGroupInformation.getCurrentUser().getShortUserName() : null;
      try {
        return callerUGI.doAs(new PrivilegedExceptionAction<HttpURLConnection>() {
          @Override
          public HttpURLConnection run() throws Exception {
            return new DelegationTokenAuthenticatedURL(
                authenticator, connConfigurator).openConnection(url, token,
                doAsUser);
          }
        });
      } catch (UndeclaredThrowableException e) {
        throw new IOException(e.getCause());
      } catch (InterruptedException e) {
        throw new IOException(e);
      }
    }

  }

  private static ConnectionConfigurator newConnConfigurator(Configuration conf) {
    try {
      return newSslConnConfigurator(DEFAULT_SOCKET_TIMEOUT, conf);
    } catch (Exception e) {
      LOG.debug("Cannot load customized ssl related configuration. " +
          "Fallback to system-generic settings.", e);
      return DEFAULT_TIMEOUT_CONN_CONFIGURATOR;
    }
  }

  private static final ConnectionConfigurator DEFAULT_TIMEOUT_CONN_CONFIGURATOR =
      new ConnectionConfigurator() {
    @Override
    public HttpURLConnection configure(HttpURLConnection conn)
        throws IOException {
      setTimeouts(conn, DEFAULT_SOCKET_TIMEOUT);
      return conn;
    }
  };

  private static ConnectionConfigurator newSslConnConfigurator(final int timeout,
      Configuration conf) throws IOException, GeneralSecurityException {
    final SSLFactory factory;
    final SSLSocketFactory sf;
    final HostnameVerifier hv;

    factory = new SSLFactory(SSLFactory.Mode.CLIENT, conf);
    factory.init();
    sf = factory.createSSLSocketFactory();
    hv = factory.getHostnameVerifier();

    return new ConnectionConfigurator() {
      @Override
      public HttpURLConnection configure(HttpURLConnection conn)
          throws IOException {
        if (conn instanceof HttpsURLConnection) {
          HttpsURLConnection c = (HttpsURLConnection) conn;
          c.setSSLSocketFactory(sf);
          c.setHostnameVerifier(hv);
        }
        setTimeouts(conn, timeout);
        return conn;
      }
    };
  }

  private static void setTimeouts(URLConnection connection, int socketTimeout) {
    connection.setConnectTimeout(socketTimeout);
    connection.setReadTimeout(socketTimeout);
  }

  public static void main(String[] argv) throws Exception {
    CommandLine cliParser = new GnuParser().parse(opts, argv);
    if (cliParser.hasOption("put")) {
      String path = cliParser.getOptionValue("put");
      if (path != null && path.length() > 0) {
        if (cliParser.hasOption(ENTITY_DATA_TYPE)) {
          putTimelineDataInJSONFile(path, ENTITY_DATA_TYPE);
          return;
        } else if (cliParser.hasOption(DOMAIN_DATA_TYPE)) {
          putTimelineDataInJSONFile(path, DOMAIN_DATA_TYPE);
          return;
        }
      }
    }
    printUsage();
  }

  /**
   * Put timeline data in a JSON file via command line.
   * 
   * @param path
   *          path to the timeline data JSON file
   * @param type
   *          the type of the timeline data in the JSON file
   */
  private static void putTimelineDataInJSONFile(String path, String type) {
    File jsonFile = new File(path);
    if (!jsonFile.exists()) {
      LOG.error("File [" + jsonFile.getAbsolutePath() + "] doesn't exist");
      return;
    }
    ObjectMapper mapper = new ObjectMapper();
    YarnJacksonJaxbJsonProvider.configObjectMapper(mapper);
    TimelineEntities entities = null;
    TimelineDomains domains = null;
    try {
      if (type.equals(ENTITY_DATA_TYPE)) {
        entities = mapper.readValue(jsonFile, TimelineEntities.class);
      } else if (type.equals(DOMAIN_DATA_TYPE)){
        domains = mapper.readValue(jsonFile, TimelineDomains.class);
      }
    } catch (Exception e) {
      LOG.error("Error when reading  " + e.getMessage());
      e.printStackTrace(System.err);
      return;
    }
    Configuration conf = new YarnConfiguration();
    TimelineClient client = TimelineClient.createTimelineClient();
    client.init(conf);
    client.start();
    try {
      if (UserGroupInformation.isSecurityEnabled()
          && conf.getBoolean(YarnConfiguration.TIMELINE_SERVICE_ENABLED, false)) {
        Token<TimelineDelegationTokenIdentifier> token =
            client.getDelegationToken(
                UserGroupInformation.getCurrentUser().getUserName());
        UserGroupInformation.getCurrentUser().addToken(token);
      }
      if (type.equals(ENTITY_DATA_TYPE)) {
        TimelinePutResponse response = client.putEntities(
            entities.getEntities().toArray(
                new TimelineEntity[entities.getEntities().size()]));
        if (response.getErrors().size() == 0) {
          LOG.info("Timeline entities are successfully put");
        } else {
          for (TimelinePutResponse.TimelinePutError error : response.getErrors()) {
            LOG.error("TimelineEntity [" + error.getEntityType() + ":" +
                error.getEntityId() + "] is not successfully put. Error code: " +
                error.getErrorCode());
          }
        }
      } else if (type.equals(DOMAIN_DATA_TYPE)) {
        boolean hasError = false;
        for (TimelineDomain domain : domains.getDomains()) {
          try {
            client.putDomain(domain);
          } catch (Exception e) {
            LOG.error("Error when putting domain " + domain.getId(), e);
            hasError = true;
          }
        }
        if (!hasError) {
          LOG.info("Timeline domains are successfully put");
        }
      }
    } catch(RuntimeException e) {
      LOG.error("Error when putting the timeline data", e);
    } catch (Exception e) {
      LOG.error("Error when putting the timeline data", e);
    } finally {
      client.stop();
    }
  }

  /**
   * Helper function to print out usage
   */
  private static void printUsage() {
    new HelpFormatter().printHelp("TimelineClient", opts);
  }

}
