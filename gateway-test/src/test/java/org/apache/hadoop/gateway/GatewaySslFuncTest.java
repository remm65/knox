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
package org.apache.hadoop.gateway;

import java.io.File;
import java.net.URL;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Properties;
import java.util.ServiceLoader;
import java.util.UUID;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import javax.xml.transform.stream.StreamSource;

import org.apache.commons.io.FileUtils;
import org.apache.directory.server.protocol.shared.transport.TcpTransport;
import org.apache.hadoop.gateway.security.ldap.SimpleLdapDirectoryServer;
import org.apache.hadoop.gateway.services.DefaultGatewayServices;
import org.apache.hadoop.gateway.services.GatewayServices;
import org.apache.hadoop.gateway.services.ServiceLifecycleException;
import org.apache.hadoop.gateway.services.topology.TopologyService;
import org.apache.hadoop.test.TestUtils;
import org.apache.hadoop.test.category.ReleaseTest;
import org.apache.hadoop.test.mock.MockServer;
import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.AuthCache;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.impl.auth.BasicScheme;
import org.apache.http.impl.client.BasicAuthCache;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.log4j.Appender;
import org.hamcrest.MatcherAssert;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.apache.hadoop.test.TestUtils.LOG_ENTER;
import static org.apache.hadoop.test.TestUtils.LOG_EXIT;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;
import static org.xmlmatchers.transform.XmlConverters.the;
import static org.xmlmatchers.xpath.HasXPath.hasXPath;

@Category( ReleaseTest.class )
public class GatewaySslFuncTest {

  private static Logger LOG = LoggerFactory.getLogger( GatewaySslFuncTest.class );
  private static Class DAT = GatewaySslFuncTest.class;

  private static Enumeration<Appender> appenders;
  private static GatewayTestConfig config;
  private static DefaultGatewayServices services;
  private static GatewayServer gateway;
  private static String gatewayScheme;
  private static int gatewayPort;
  private static String gatewayUrl;
  private static SimpleLdapDirectoryServer ldap;
  private static TcpTransport ldapTransport;
  private static int ldapPort;
  private static Properties params;
  private static TopologyService topos;
  private static MockServer mockWebHdfs;

  @BeforeClass
  public static void setupSuite() throws Exception {
    LOG_ENTER();
    //appenders = NoOpAppender.setUp();
    setupLdap();
    setupGateway();
    LOG_EXIT();
  }

  @AfterClass
  public static void cleanupSuite() throws Exception {
    LOG_ENTER();
    gateway.stop();
    ldap.stop( true );
    FileUtils.deleteQuietly( new File( config.getGatewayHomeDir() ) );
    //NoOpAppender.tearDown( appenders );
    LOG_EXIT();
  }

  @After
  public void cleanupTest() throws Exception {
    FileUtils.cleanDirectory( new File( config.getGatewayTopologyDir() ) );
    FileUtils.cleanDirectory( new File( config.getGatewayDeploymentDir() ) );
  }

  public static void setupLdap() throws Exception {
    URL usersUrl = TestUtils.getResourceUrl( DAT, "users.ldif" );
    ldapTransport = new TcpTransport( 0 );
    ldap = new SimpleLdapDirectoryServer( "dc=hadoop,dc=apache,dc=org", new File( usersUrl.toURI() ), ldapTransport );
    ldap.start();
    LOG.info( "LDAP port = " + ldapTransport.getAcceptor().getLocalAddress().getPort() );
  }

  public static void setupGateway() throws Exception {

    File targetDir = new File( System.getProperty( "user.dir" ), "target" );
    File gatewayDir = new File( targetDir, "gateway-home-" + UUID.randomUUID() );
    gatewayDir.mkdirs();

    config = new GatewayTestConfig();
    config.setGatewayHomeDir( gatewayDir.getAbsolutePath() );

    File topoDir = new File( config.getGatewayTopologyDir() );
    topoDir.mkdirs();

    File deployDir = new File( config.getGatewayDeploymentDir() );
    deployDir.mkdirs();

    File securityDir = new File( config.getGatewaySecurityDir() );
    securityDir.mkdirs();

    config.setSSLEnabled( true );

    setupMockServers();
    startGatewayServer();
  }

  public static void setupMockServers() throws Exception {
    mockWebHdfs = new MockServer( "WEBHDFS", true );
  }

  private static GatewayServices instantiateGatewayServices() {
    ServiceLoader<GatewayServices> loader = ServiceLoader.load( GatewayServices.class );
    Iterator<GatewayServices> services = loader.iterator();
    if (services.hasNext()) {
      return services.next();
    }
    return null;
  }

  public static void startGatewayServer() throws Exception {
    instantiateGatewayServices();
    services = new DefaultGatewayServices();
    Map<String,String> options = new HashMap<String,String>();
    options.put( "persist-master", "false" );
    options.put( "master", "password" );
    try {
      services.init( config, options );
    } catch ( ServiceLifecycleException e ) {
      e.printStackTrace(); // I18N not required.
    }
    topos = services.getService(GatewayServices.TOPOLOGY_SERVICE);

    gateway = GatewayServer.startGateway( config, services );
    MatcherAssert.assertThat( "Failed to start gateway.", gateway, notNullValue() );

    gatewayScheme = config.isSSLEnabled() ? "https" : "http";
    gatewayPort = gateway.getAddresses()[0].getPort();
    gatewayUrl = gatewayScheme + "://localhost:" + gatewayPort + "/" + config.getGatewayPath();

    LOG.info( "Gateway port = " + gateway.getAddresses()[ 0 ].getPort() );

    params = new Properties();
    params.put( "LDAP_URL", "ldap://localhost:" + ldapTransport.getAcceptor().getLocalAddress().getPort() );
    params.put( "WEBHDFS_URL", "http://localhost:" + mockWebHdfs.getPort() );
  }

  @Test( timeout = TestUtils.MEDIUM_TIMEOUT )
  public void testKnox674SslCipherSuiteConfig() throws Exception {
    LOG_ENTER();

    String topoStr = TestUtils.merge( DAT, "test-admin-topology.xml", params );
    File topoFile = new File( config.getGatewayTopologyDir(), "test-topology.xml" );
    FileUtils.writeStringToFile( topoFile, topoStr );

    topos.reloadTopologies();

    String username = "guest";
    String password = "guest-password";
    String serviceUrl = gatewayUrl + "/test-topology/api/v1/version";

    HttpHost targetHost = new HttpHost( "localhost", gatewayPort, gatewayScheme );
    CredentialsProvider credsProvider = new BasicCredentialsProvider();
    credsProvider.setCredentials(
        new AuthScope( targetHost.getHostName(), targetHost.getPort() ),
        new UsernamePasswordCredentials( username, password ) );

    AuthCache authCache = new BasicAuthCache();
    BasicScheme basicAuth = new BasicScheme();
    authCache.put( targetHost, basicAuth );

    HttpClientContext context = HttpClientContext.create();
    context.setCredentialsProvider( credsProvider );
    context.setAuthCache( authCache );

    CloseableHttpClient client = HttpClients.custom()
        .setSSLSocketFactory(
            new SSLConnectionSocketFactory(
                createInsecureSslContext(),
                new String[]{"TLSv1.2"},
                new String[]{"TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA256"},
                new TrustAllHosts() ) )
        .build();
    HttpGet request = new HttpGet( serviceUrl );
    CloseableHttpResponse response = client.execute( request, context );
    assertThat( the( new StreamSource( response.getEntity().getContent() ) ), hasXPath( "/ServerVersion/version" ) );
    response.close();
    client.close();

    gateway.stop();
    config.setExcludedSSLCiphers( Arrays.asList( new String[]{ "TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA256" } ) );
    config.setIncludedSSLCiphers( Arrays.asList( new String[]{ "TLS_DHE_RSA_WITH_AES_128_CBC_SHA" } ) );

    startGatewayServer();
    serviceUrl = gatewayUrl + "/test-topology/api/v1/version";

    try {
      client = HttpClients.custom()
          .setSSLSocketFactory(
              new SSLConnectionSocketFactory(
                  createInsecureSslContext(),
                  new String[]{ "TLSv1.2" },
                  new String[]{ "TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA256" },
                  new TrustAllHosts() ) ).build();
      request = new HttpGet( serviceUrl );
      client.execute( request, context );
      fail( "Expected SSLHandshakeException" );
    } catch ( SSLHandshakeException e ) {
      // Expected.
      client.close();
    }

    client = HttpClients.custom()
        .setSSLSocketFactory(
            new SSLConnectionSocketFactory(
                createInsecureSslContext(),
                new String[]{ "TLSv1.2" },
                new String[]{ "TLS_DHE_RSA_WITH_AES_128_CBC_SHA" },
                new TrustAllHosts() ) ).build();
    request = new HttpGet( serviceUrl );
    response = client.execute( request, context );
    assertThat( the( new StreamSource( response.getEntity().getContent() ) ), hasXPath( "/ServerVersion/version" ) );
    response.close();
    client.close();

    LOG_EXIT();
  }

  public static class TrustAllHosts implements HostnameVerifier {
    @Override
    public boolean verify( String host, SSLSession sslSession ) {
      // Trust all hostnames.
      return true;
    }
  }

  public static class TrustAllCerts implements X509TrustManager {

    public void checkClientTrusted( X509Certificate[] x509Certificates, String s ) throws CertificateException {
      // Trust all certificates.
    }

    public void checkServerTrusted( X509Certificate[] x509Certificates, String s ) throws CertificateException {
      // Trust all certificates.
    }

    public X509Certificate[] getAcceptedIssuers() {
      return null;
    }

  }

  public static SSLContext createInsecureSslContext() throws NoSuchAlgorithmException, KeyManagementException {
    SSLContext sslContext = SSLContext.getInstance( "SSL" );
    sslContext.init( null, new TrustManager[]{ new TrustAllCerts() }, new SecureRandom() );
    return sslContext;
  }

}
