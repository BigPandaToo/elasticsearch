/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.security.enrollment;

import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.common.CheckedSupplier;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.settings.MockSecureSettings;
import org.elasticsearch.common.settings.SecureString;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.ssl.SslConfigException;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.common.xcontent.json.JsonXContent;
import org.elasticsearch.core.CheckedFunction;
import org.elasticsearch.env.Environment;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.xpack.core.security.user.ElasticUser;
import org.elasticsearch.xpack.security.tool.CommandLineHttpClient;
import org.elasticsearch.xpack.security.tool.HttpResponse;
import org.hamcrest.Matchers;
import org.junit.Before;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.elasticsearch.xpack.security.enrollment.CreateEnrollmentToken.getFilteredAddresses;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class CreateEnrollmentTokenTests extends ESTestCase {
    private Environment environment;

    @Before
    public void setupMocks() throws Exception {
        final Path tempDir = createTempDir();
        final Path httpCaPath = tempDir.resolve("httpCa.p12");
        Files.copy(getDataPath("/org/elasticsearch/xpack/security/action/enrollment/httpCa.p12"), httpCaPath);

        final MockSecureSettings secureSettings = new MockSecureSettings();
        secureSettings.setString("xpack.http.ssl.truststore.secure_password", "password");
        secureSettings.setString("xpack.security.http.ssl.keystore.secure_password", "password");
        final Settings settings = Settings.builder()
            .put("xpack.security.enabled", true)
            .put("xpack.http.ssl.enabled", true)
            .put( "xpack.security.authc.api_key.enabled", true)
            .put("xpack.http.ssl.truststore.path", "httpCa.p12")
            .put("xpack.security.http.ssl.enabled", true)
            .put("xpack.security.http.ssl.keystore.path", "httpCa.p12")
            .put("xpack.security.enrollment.enabled", "true")
            .setSecureSettings(secureSettings)
            .put("path.home", tempDir)
            .build();
        environment = new Environment(settings, tempDir);
    }

    public void testCreateSuccess() throws Exception {
        final CommandLineHttpClient client = mock(CommandLineHttpClient.class);
        when(client.getDefaultURL()).thenReturn("http://localhost:9200");
        final URL defaultUrl = new URL(client.getDefaultURL());
        final URL createAPIKeyURL = CreateEnrollmentToken.createAPIKeyURL(defaultUrl);
        final URL getHttpInfoURL = CreateEnrollmentToken.getHttpInfoURL(defaultUrl);

        final HttpResponse httpResponseOK = new HttpResponse(HttpURLConnection.HTTP_OK, new HashMap<String, Object>());
        when(client.execute(anyString(), any(URL.class), anyString(), any(SecureString.class), any(CheckedSupplier.class),
            any(CheckedFunction.class))).thenReturn(httpResponseOK);

        String createApiKeyResponseBody;
        try (XContentBuilder builder = XContentFactory.contentBuilder(XContentType.JSON)) {
            builder.startObject()
                .field("id", "DR6CzXkBDf8amV_48yYX")
                .field("name", "enrollment_token_API_key_VuaCfGcBCdbkQm")
                .field("expiration", "1622652381786")
                .field("api_key", "x3YqU_rqQwm-ESrkExcnOg")
                .endObject();
            createApiKeyResponseBody = Strings.toString(builder);
        }
        when(client.execute(eq("POST"), eq(createAPIKeyURL), eq(ElasticUser.NAME), any(SecureString.class),
            any(CheckedSupplier.class), any(CheckedFunction.class)))
            .thenReturn(createHttpResponse(HttpURLConnection.HTTP_OK, createApiKeyResponseBody));

        String getHttpInfoResponseBody;
        try (XContentBuilder builder = XContentFactory.contentBuilder(XContentType.JSON)) {
            builder.startObject()
                .startObject("nodes")
                .startObject("sxLDrFu8SnKepObrEOjPZQ")
                .field("version", "8.0.0")
                .startObject("http")
                .startArray("bound_address")
                .value("[::1]:9200")
                .value("127.0.0.1:9200")
                .value("192.168.0.1:9201")
                .value("172.16.254.1:9202")
                .value("[2001:db8:0:1234:0:567:8:1]:9203")
                .endArray()
                .field("publish_address", "127.0.0.1:9200")
                .endObject().endObject().endObject().endObject();
            getHttpInfoResponseBody = Strings.toString(builder);
        }
        when(client.execute(eq("GET"), eq(getHttpInfoURL), eq(ElasticUser.NAME), any(SecureString.class),
            any(CheckedSupplier.class), any(CheckedFunction.class)))
            .thenReturn(createHttpResponse(HttpURLConnection.HTTP_OK, getHttpInfoResponseBody));

        final CreateEnrollmentToken createEnrollmentToken = new CreateEnrollmentToken(environment, client);

        final String token = createEnrollmentToken.create("elastic", new SecureString("elastic"), new SecureString("password"));

        Map<String, String> info = getDecoded(token);
        assertEquals("8.0.0", info.get("ver"));
        assertEquals("[192.168.0.1:9201, 172.16.254.1:9202, [2001:db8:0:1234:0:567:8:1]:9203]", info.get("adr"));
        assertEquals("598a35cd831ee6bb90e79aa80d6b073cda88b41d", info.get("fgr"));
        assertEquals("x3YqU_rqQwm-ESrkExcnOg", info.get("key"));
    }

    public void testFailedCreateApiKey() throws Exception {
        final CommandLineHttpClient client = mock(CommandLineHttpClient.class);
        when(client.getDefaultURL()).thenReturn("http://localhost:9200");
        final URL defaultUrl = new URL(client.getDefaultURL());
        final URL createAPIKeyURL = CreateEnrollmentToken.createAPIKeyURL(defaultUrl);

        final HttpResponse httpResponseNotOK = new HttpResponse(HttpURLConnection.HTTP_BAD_REQUEST, new HashMap<String, Object>());
        when(client.execute(anyString(), eq(createAPIKeyURL), anyString(), any(SecureString.class), any(CheckedSupplier.class),
            any(CheckedFunction.class))).thenReturn(httpResponseNotOK);

        final CreateEnrollmentToken createEnrollmentToken = new CreateEnrollmentToken(environment, client);
        IllegalStateException ex = expectThrows(IllegalStateException.class, () -> createEnrollmentToken.create("elastic",
            new SecureString("elastic"), new SecureString("password")));
        assertThat(ex.getMessage(), Matchers.containsString("Unexpected response code [400] from calling POST "));
    }

    public void testFailedRetrieveHttpInfo() throws Exception {
        final CommandLineHttpClient client = mock(CommandLineHttpClient.class);
        when(client.getDefaultURL()).thenReturn("http://localhost:9200");
        final URL defaultUrl = new URL(client.getDefaultURL());
        final URL createAPIKeyURL = CreateEnrollmentToken.createAPIKeyURL(defaultUrl);
        final URL getHttpInfoURL = CreateEnrollmentToken.getHttpInfoURL(defaultUrl);

        final HttpResponse httpResponseOK = new HttpResponse(HttpURLConnection.HTTP_OK, new HashMap<String, Object>());
        when(client.execute(anyString(), any(URL.class), anyString(), any(SecureString.class), any(CheckedSupplier.class),
            any(CheckedFunction.class))).thenReturn(httpResponseOK);

        String createApiKeyResponseBody;
        try (XContentBuilder builder = XContentFactory.contentBuilder(XContentType.JSON)) {
            builder.startObject()
                .field("id", "DR6CzXkBDf8amV_48yYX")
                .field("name", "enrollment_token_API_key_VuaCfGcBCdbkQm")
                .field("expiration", "1622652381786")
                .field("api_key", "x3YqU_rqQwm-ESrkExcnOg")
                .endObject();
            createApiKeyResponseBody = Strings.toString(builder);
        }
        when(client.execute(eq("POST"), eq(createAPIKeyURL), eq(ElasticUser.NAME), any(SecureString.class),
            any(CheckedSupplier.class), any(CheckedFunction.class)))
            .thenReturn(createHttpResponse(HttpURLConnection.HTTP_OK, createApiKeyResponseBody));

        final HttpResponse httpResponseNotOK = new HttpResponse(HttpURLConnection.HTTP_BAD_REQUEST, new HashMap<String, Object>());
        when(client.execute(anyString(), eq(getHttpInfoURL), anyString(), any(SecureString.class), any(CheckedSupplier.class),
            any(CheckedFunction.class))).thenReturn(httpResponseNotOK);

        final CreateEnrollmentToken createEnrollmentToken = new CreateEnrollmentToken(environment, client);
        IllegalStateException ex = expectThrows(IllegalStateException.class, () -> createEnrollmentToken.create("elastic",
            new SecureString("elastic"), new SecureString("password")));
        assertThat(ex.getMessage(), Matchers.containsString("Unexpected response code [400] from calling GET "));
    }

    public void testNoKeyStore() throws Exception {
        final Path tempDir = createTempDir();
        final Path httpCaPath = tempDir.resolve("httpCa.p12");
        Files.copy(getDataPath("/org/elasticsearch/xpack/security/action/enrollment/httpCa.p12"), httpCaPath);
        final MockSecureSettings secureSettings = new MockSecureSettings();
        secureSettings.setString("xpack.http.ssl.truststore.secure_password", "password");
        final Settings settings = Settings.builder()
            .put("xpack.security.enabled", true)
            .put("xpack.http.ssl.enabled", true)
            .put( "xpack.security.authc.api_key.enabled", true)
            .put("xpack.http.ssl.truststore.path", "httpCa.p12")
            .put("xpack.security.http.ssl.enabled", true)
            .put("xpack.security.enrollment.enabled", "true")
            .setSecureSettings(secureSettings)
            .put("path.home", tempDir)
            .build();
        final Environment environment_no_keystore = new Environment(settings, tempDir);
        final CommandLineHttpClient client = mock(CommandLineHttpClient.class);

        CreateEnrollmentToken createEnrollmentToken = new CreateEnrollmentToken(environment_no_keystore, client);
        IllegalStateException ex = expectThrows(IllegalStateException.class, () -> createEnrollmentToken.create("elastic",
            new SecureString("elastic"), new SecureString("password")));
        assertThat(ex.getMessage(), Matchers.containsString("'xpack.security.http.ssl.keystore.path' is not configured"));
    }

    public void testEnrollmentNotEnabled() throws Exception {
        final Path tempDir = createTempDir();
        final Path httpCaPath = tempDir.resolve("httpCa.p12");
        Files.copy(getDataPath("/org/elasticsearch/xpack/security/action/enrollment/httpCa.p12"), httpCaPath);

        final MockSecureSettings secureSettings = new MockSecureSettings();
        secureSettings.setString("xpack.http.ssl.truststore.secure_password", "password");
        secureSettings.setString("xpack.security.http.ssl.keystore.secure_password", "password");
        final Settings settings = Settings.builder()
            .put("xpack.security.enabled", true)
            .put("xpack.http.ssl.enabled", true)
            .put( "xpack.security.authc.api_key.enabled", true)
            .put("xpack.http.ssl.truststore.path", "httpCa.p12")
            .put("xpack.security.http.ssl.enabled", true)
            .put("xpack.security.http.ssl.keystore.path", "httpCa.p12")
            .setSecureSettings(secureSettings)
            .put("path.home", tempDir)
            .build();
        final Environment environment_not_enabled = new Environment(settings, tempDir);
        final CommandLineHttpClient client = mock(CommandLineHttpClient.class);

        CreateEnrollmentToken createEnrollmentToken = new CreateEnrollmentToken(environment_not_enabled, client);
        IllegalStateException ex = expectThrows(IllegalStateException.class, () -> createEnrollmentToken.create("elastic",
            new SecureString("elastic"), new SecureString("password")));
        assertThat(ex.getMessage(), Matchers.equalTo("'xpack.security.enrollment' must be enabled to create an enrollment token"));
    }

    public void testReadKeystoreWrongPassword () {
        Path path = environment.configFile().resolve(environment.settings().get("xpack.security.http.ssl.keystore.path"));
        SslConfigException ex = expectThrows(SslConfigException.class, () -> CreateEnrollmentToken.readKeyStore(path,
            new SecureString("wrong_password")));
        assertThat(ex.getMessage(), Matchers.startsWith("cannot read a [PKCS12] keystore from "));
    }

    public void testReadKeystoreWrongPath () {
        Path path = environment.configFile().resolve("");
        SslConfigException ex = expectThrows(SslConfigException.class, () -> CreateEnrollmentToken.readKeyStore(path,
            new SecureString("wrong_password")));
        assertThat(ex.getMessage(), Matchers.startsWith("cannot read a [jks] keystore from "));
    }

    public void testGetPrivateKeyEntriesWrongPassword () throws Exception {
        Path path = environment.configFile().resolve(environment.settings().get("xpack.security.http.ssl.keystore.path"));
        KeyStore keyStore = CreateEnrollmentToken.readKeyStore(path, new SecureString("password"));
        ElasticsearchException ex = expectThrows(ElasticsearchException.class, () -> CreateEnrollmentToken.getPrivateKeyEntries(keyStore,
            new SecureString("wrong_password")));
        assertThat(ex.getMessage(), Matchers.startsWith("failed to list keys and certificates"));
    }

    public void testGetPrivateKeyEntriesNullKeyStore () {
        ElasticsearchException ex = expectThrows(ElasticsearchException.class, () -> CreateEnrollmentToken.getPrivateKeyEntries(null,
            new SecureString("wrong_password")));
        assertThat(ex.getMessage(), Matchers.startsWith("failed to list keys and certificates"));
    }

    public void testGetFilteredAddresses () throws Exception {
        List<String> addresses = Arrays.asList("[::1]:9200", "127.0.0.1:9200", "192.168.0.1:9201", "172.16.254.1:9202",
            "[2001:db8:0:1234:0:567:8:1]:9203");
        List<String> filteredAddresses = getFilteredAddresses(addresses);
        assertThat(filteredAddresses.size(), Matchers.equalTo(3));
        assertThat(filteredAddresses, Matchers.containsInAnyOrder("192.168.0.1:9201", "172.16.254.1:9202",
            "[2001:db8:0:1234:0:567:8:1]:9203"));

        addresses = Arrays.asList("[::1]:9200", "127.0.0.1:9200");
        filteredAddresses = getFilteredAddresses(addresses);
        assertThat(filteredAddresses.size(), Matchers.equalTo(2));
        assertThat(filteredAddresses, Matchers.containsInAnyOrder("[::1]:9200", "127.0.0.1:9200"));

        addresses = Arrays.asList("192.168.0.1:9201", "172.16.254.1:9202", "[2001:db8:0:1234:0:567:8:1]:9203");
        filteredAddresses = getFilteredAddresses(addresses);
        assertThat(filteredAddresses.size(), Matchers.equalTo(3));
        assertThat(filteredAddresses, Matchers.containsInAnyOrder("192.168.0.1:9201", "172.16.254.1:9202",
            "[2001:db8:0:1234:0:567:8:1]:9203"));

        final List<String> invalid_addresses = Arrays.asList("nldfnbndflbnl");
        UnknownHostException ex = expectThrows(UnknownHostException.class, () -> getFilteredAddresses(invalid_addresses));
        assertThat(ex.getMessage(), Matchers.startsWith("nldfnbndflbnl:"));
    }

    private Map<String, String> getDecoded(String token) throws IOException {
        final String jsonString = new String(Base64.getDecoder().decode(token), StandardCharsets.UTF_8);
        try (XContentParser parser = createParser(JsonXContent.jsonXContent, jsonString)) {
            final Map<String, Object> info = parser.map();
            assertNotEquals(info, null);
            return info.entrySet().stream()
                .collect(Collectors.toMap(e -> e.getKey(), e -> e.getValue().toString()));
        }
    }

    private HttpResponse createHttpResponse(final int httpStatus, final String responseJson) throws IOException {
        final HttpResponse.HttpResponseBuilder builder = new HttpResponse.HttpResponseBuilder();
        builder.withHttpStatus(httpStatus);
        builder.withResponseBody(responseJson);
        return builder.build();
    }
}
