/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.fs.azurebfs.services;

import java.io.Closeable;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Strings;
import org.apache.hadoop.security.ssl.DelegatingSSLSocketFactory;
import org.apache.hadoop.fs.azurebfs.constants.AbfsHttpConstants;
import org.apache.hadoop.fs.azurebfs.constants.HttpHeaderConfigurations;
import org.apache.hadoop.fs.azurebfs.constants.HttpQueryParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.hadoop.fs.azurebfs.contracts.exceptions.AzureBlobFileSystemException;
import org.apache.hadoop.fs.azurebfs.contracts.exceptions.InvalidUriException;
import org.apache.hadoop.fs.azurebfs.contracts.exceptions.SASTokenProviderException;
import org.apache.hadoop.fs.azurebfs.extensions.ExtensionHelper;
import org.apache.hadoop.fs.azurebfs.extensions.SASTokenProvider;
import org.apache.hadoop.fs.azurebfs.AbfsConfiguration;
import org.apache.hadoop.fs.azurebfs.oauth2.AccessTokenProvider;
import org.apache.hadoop.io.IOUtils;

import static org.apache.hadoop.fs.azurebfs.constants.AbfsHttpConstants.*;
import static org.apache.hadoop.fs.azurebfs.constants.FileSystemUriSchemes.HTTPS_SCHEME;
import static org.apache.hadoop.fs.azurebfs.constants.HttpHeaderConfigurations.*;
import static org.apache.hadoop.fs.azurebfs.constants.HttpQueryParams.*;

/**
 * AbfsClient.
 */
public class AbfsClient implements Closeable {
  public static final Logger LOG = LoggerFactory.getLogger(AbfsClient.class);
  private final URL baseUrl;
  private final SharedKeyCredentials sharedKeyCredentials;
  private final String xMsVersion = "2018-11-09";
  private final ExponentialRetryPolicy retryPolicy;
  private final String filesystem;
  private final AbfsConfiguration abfsConfiguration;
  private final String userAgent;
  private final AbfsPerfTracker abfsPerfTracker;

  private final String accountName;
  private final AuthType authType;
  private AccessTokenProvider tokenProvider;
  private SASTokenProvider sasTokenProvider;

  private AbfsClient(final URL baseUrl, final SharedKeyCredentials sharedKeyCredentials,
                    final AbfsConfiguration abfsConfiguration,
                    final ExponentialRetryPolicy exponentialRetryPolicy,
                    final AbfsPerfTracker abfsPerfTracker) {
    this.baseUrl = baseUrl;
    this.sharedKeyCredentials = sharedKeyCredentials;
    String baseUrlString = baseUrl.toString();
    this.filesystem = baseUrlString.substring(baseUrlString.lastIndexOf(FORWARD_SLASH) + 1);
    this.abfsConfiguration = abfsConfiguration;
    this.retryPolicy = exponentialRetryPolicy;
    this.accountName = abfsConfiguration.getAccountName().substring(0, abfsConfiguration.getAccountName().indexOf(AbfsHttpConstants.DOT));
    this.authType = abfsConfiguration.getAuthType(accountName);

    String sslProviderName = null;

    if (this.baseUrl.toString().startsWith(HTTPS_SCHEME)) {
      try {
        LOG.trace("Initializing DelegatingSSLSocketFactory with {} SSL "
                + "Channel Mode", this.abfsConfiguration.getPreferredSSLFactoryOption());
        DelegatingSSLSocketFactory.initializeDefaultFactory(this.abfsConfiguration.getPreferredSSLFactoryOption());
        sslProviderName = DelegatingSSLSocketFactory.getDefaultFactory().getProviderName();
      } catch (IOException e) {
        // Suppress exception. Failure to init DelegatingSSLSocketFactory would have only performance impact.
        LOG.trace("NonCritFailure: DelegatingSSLSocketFactory Init failed : "
            + "{}", e.getMessage());
      }
    }

    this.userAgent = initializeUserAgent(abfsConfiguration, sslProviderName);
    this.abfsPerfTracker = abfsPerfTracker;
  }

  public AbfsClient(final URL baseUrl, final SharedKeyCredentials sharedKeyCredentials,
                    final AbfsConfiguration abfsConfiguration,
                    final ExponentialRetryPolicy exponentialRetryPolicy,
                    final AccessTokenProvider tokenProvider,
                    final AbfsPerfTracker abfsPerfTracker) {
    this(baseUrl, sharedKeyCredentials, abfsConfiguration, exponentialRetryPolicy, abfsPerfTracker);
    this.tokenProvider = tokenProvider;
  }

  public AbfsClient(final URL baseUrl, final SharedKeyCredentials sharedKeyCredentials,
                    final AbfsConfiguration abfsConfiguration,
                    final ExponentialRetryPolicy exponentialRetryPolicy,
                    final SASTokenProvider sasTokenProvider,
                    final AbfsPerfTracker abfsPerfTracker) {
    this(baseUrl, sharedKeyCredentials, abfsConfiguration, exponentialRetryPolicy, abfsPerfTracker);
    this.sasTokenProvider = sasTokenProvider;
  }

  @Override
  public void close() throws IOException {
    if (tokenProvider instanceof Closeable) {
      IOUtils.cleanupWithLogger(LOG, (Closeable) tokenProvider);
    }
  }

  public String getFileSystem() {
    return filesystem;
  }

  protected AbfsPerfTracker getAbfsPerfTracker() {
    return abfsPerfTracker;
  }

  ExponentialRetryPolicy getRetryPolicy() {
    return retryPolicy;
  }

  SharedKeyCredentials getSharedKeyCredentials() {
    return sharedKeyCredentials;
  }

  List<AbfsHttpHeader> createDefaultHeaders() {
    final List<AbfsHttpHeader> requestHeaders = new ArrayList<AbfsHttpHeader>();
    requestHeaders.add(new AbfsHttpHeader(X_MS_VERSION, xMsVersion));
    requestHeaders.add(new AbfsHttpHeader(ACCEPT, APPLICATION_JSON
            + COMMA + SINGLE_WHITE_SPACE + APPLICATION_OCTET_STREAM));
    requestHeaders.add(new AbfsHttpHeader(ACCEPT_CHARSET,
            UTF_8));
    requestHeaders.add(new AbfsHttpHeader(CONTENT_TYPE, EMPTY_STRING));
    requestHeaders.add(new AbfsHttpHeader(USER_AGENT, userAgent));
    return requestHeaders;
  }

  AbfsUriQueryBuilder createDefaultUriQueryBuilder() {
    final AbfsUriQueryBuilder abfsUriQueryBuilder = new AbfsUriQueryBuilder();
    abfsUriQueryBuilder.addQuery(QUERY_PARAM_TIMEOUT, DEFAULT_TIMEOUT);
    return abfsUriQueryBuilder;
  }

  public AbfsRestOperation createFilesystem() throws AzureBlobFileSystemException {
    final List<AbfsHttpHeader> requestHeaders = createDefaultHeaders();

    final AbfsUriQueryBuilder abfsUriQueryBuilder = new AbfsUriQueryBuilder();
    abfsUriQueryBuilder.addQuery(QUERY_PARAM_RESOURCE, FILESYSTEM);

    final URL url = createRequestUrl(abfsUriQueryBuilder.toString());
    final AbfsRestOperation op = new AbfsRestOperation(
            AbfsRestOperationType.CreateFileSystem,
            this,
            HTTP_METHOD_PUT,
            url,
            requestHeaders);
    op.execute();
    return op;
  }

  public AbfsRestOperation setFilesystemProperties(final String properties) throws AzureBlobFileSystemException {
    final List<AbfsHttpHeader> requestHeaders = createDefaultHeaders();
    // JDK7 does not support PATCH, so to workaround the issue we will use
    // PUT and specify the real method in the X-Http-Method-Override header.
    requestHeaders.add(new AbfsHttpHeader(X_HTTP_METHOD_OVERRIDE,
            HTTP_METHOD_PATCH));

    requestHeaders.add(new AbfsHttpHeader(X_MS_PROPERTIES,
            properties));

    final AbfsUriQueryBuilder abfsUriQueryBuilder = createDefaultUriQueryBuilder();
    abfsUriQueryBuilder.addQuery(QUERY_PARAM_RESOURCE, FILESYSTEM);

    final URL url = createRequestUrl(abfsUriQueryBuilder.toString());
    final AbfsRestOperation op = new AbfsRestOperation(
            AbfsRestOperationType.SetFileSystemProperties,
            this,
            HTTP_METHOD_PUT,
            url,
            requestHeaders);
    op.execute();
    return op;
  }

  public AbfsRestOperation listPath(final String relativePath, final boolean recursive, final int listMaxResults,
                                    final String continuation) throws AzureBlobFileSystemException {
    final List<AbfsHttpHeader> requestHeaders = createDefaultHeaders();

    final AbfsUriQueryBuilder abfsUriQueryBuilder = createDefaultUriQueryBuilder();
    abfsUriQueryBuilder.addQuery(QUERY_PARAM_RESOURCE, FILESYSTEM);
    abfsUriQueryBuilder.addQuery(QUERY_PARAM_DIRECTORY, getDirectoryQueryParameter(relativePath));
    abfsUriQueryBuilder.addQuery(QUERY_PARAM_RECURSIVE, String.valueOf(recursive));
    abfsUriQueryBuilder.addQuery(QUERY_PARAM_CONTINUATION, continuation);
    abfsUriQueryBuilder.addQuery(QUERY_PARAM_MAXRESULTS, String.valueOf(listMaxResults));
    abfsUriQueryBuilder.addQuery(HttpQueryParams.QUERY_PARAM_UPN, String.valueOf(abfsConfiguration.isUpnUsed()));
    appendSASTokenToQuery(relativePath, SASTokenProvider.LIST_OPERATION, abfsUriQueryBuilder);

    final URL url = createRequestUrl(abfsUriQueryBuilder.toString());
    final AbfsRestOperation op = new AbfsRestOperation(
            AbfsRestOperationType.ListPaths,
            this,
            HTTP_METHOD_GET,
            url,
            requestHeaders);
    op.execute();
    return op;
  }

  public AbfsRestOperation getFilesystemProperties() throws AzureBlobFileSystemException {
    final List<AbfsHttpHeader> requestHeaders = createDefaultHeaders();

    final AbfsUriQueryBuilder abfsUriQueryBuilder = createDefaultUriQueryBuilder();
    abfsUriQueryBuilder.addQuery(QUERY_PARAM_RESOURCE, FILESYSTEM);

    final URL url = createRequestUrl(abfsUriQueryBuilder.toString());
    final AbfsRestOperation op = new AbfsRestOperation(
            AbfsRestOperationType.GetFileSystemProperties,
            this,
            HTTP_METHOD_HEAD,
            url,
            requestHeaders);
    op.execute();
    return op;
  }

  public AbfsRestOperation deleteFilesystem() throws AzureBlobFileSystemException {
    final List<AbfsHttpHeader> requestHeaders = createDefaultHeaders();

    final AbfsUriQueryBuilder abfsUriQueryBuilder = createDefaultUriQueryBuilder();
    abfsUriQueryBuilder.addQuery(QUERY_PARAM_RESOURCE, FILESYSTEM);

    final URL url = createRequestUrl(abfsUriQueryBuilder.toString());
    final AbfsRestOperation op = new AbfsRestOperation(
            AbfsRestOperationType.DeleteFileSystem,
            this,
            HTTP_METHOD_DELETE,
            url,
            requestHeaders);
    op.execute();
    return op;
  }

  public AbfsRestOperation createPath(final String path, final boolean isFile, final boolean overwrite,
                                      final String permission, final String umask) throws AzureBlobFileSystemException {
    final List<AbfsHttpHeader> requestHeaders = createDefaultHeaders();
    if (!overwrite) {
      requestHeaders.add(new AbfsHttpHeader(IF_NONE_MATCH, AbfsHttpConstants.STAR));
    }

    if (permission != null && !permission.isEmpty()) {
      requestHeaders.add(new AbfsHttpHeader(HttpHeaderConfigurations.X_MS_PERMISSIONS, permission));
    }

    if (umask != null && !umask.isEmpty()) {
      requestHeaders.add(new AbfsHttpHeader(HttpHeaderConfigurations.X_MS_UMASK, umask));
    }

    final AbfsUriQueryBuilder abfsUriQueryBuilder = createDefaultUriQueryBuilder();
    abfsUriQueryBuilder.addQuery(QUERY_PARAM_RESOURCE, isFile ? FILE : DIRECTORY);

    String operation = isFile
        ? SASTokenProvider.CREATE_FILE_OPERATION
        : SASTokenProvider.CREATE_DIRECTORY_OPERATION;
    appendSASTokenToQuery(path, operation, abfsUriQueryBuilder);

    final URL url = createRequestUrl(path, abfsUriQueryBuilder.toString());
    final AbfsRestOperation op = new AbfsRestOperation(
            AbfsRestOperationType.CreatePath,
            this,
            HTTP_METHOD_PUT,
            url,
            requestHeaders);
    op.execute();
    return op;
  }

  public AbfsRestOperation renamePath(String source, final String destination, final String continuation)
          throws AzureBlobFileSystemException {
    final List<AbfsHttpHeader> requestHeaders = createDefaultHeaders();

    String encodedRenameSource = urlEncode(FORWARD_SLASH + this.getFileSystem() + source);
    if (authType == AuthType.SAS) {
      final AbfsUriQueryBuilder srcQueryBuilder = new AbfsUriQueryBuilder();
      appendSASTokenToQuery(source, SASTokenProvider.RENAME_SOURCE_OPERATION, srcQueryBuilder);
      encodedRenameSource += srcQueryBuilder.toString();
    }

    LOG.trace("Rename source queryparam added {}", encodedRenameSource);
    requestHeaders.add(new AbfsHttpHeader(X_MS_RENAME_SOURCE, encodedRenameSource));
    requestHeaders.add(new AbfsHttpHeader(IF_NONE_MATCH, STAR));

    final AbfsUriQueryBuilder abfsUriQueryBuilder = createDefaultUriQueryBuilder();
    abfsUriQueryBuilder.addQuery(QUERY_PARAM_CONTINUATION, continuation);
    appendSASTokenToQuery(destination, SASTokenProvider.RENAME_DESTINATION_OPERATION, abfsUriQueryBuilder);

    final URL url = createRequestUrl(destination, abfsUriQueryBuilder.toString());
    final AbfsRestOperation op = new AbfsRestOperation(
            AbfsRestOperationType.RenamePath,
            this,
            HTTP_METHOD_PUT,
            url,
            requestHeaders);
    op.execute();
    return op;
  }

  public AbfsRestOperation append(final String path, final long position, final byte[] buffer, final int offset,
                                  final int length, final String cachedSasToken) throws AzureBlobFileSystemException {
    final List<AbfsHttpHeader> requestHeaders = createDefaultHeaders();
    // JDK7 does not support PATCH, so to workaround the issue we will use
    // PUT and specify the real method in the X-Http-Method-Override header.
    requestHeaders.add(new AbfsHttpHeader(X_HTTP_METHOD_OVERRIDE,
            HTTP_METHOD_PATCH));

    final AbfsUriQueryBuilder abfsUriQueryBuilder = createDefaultUriQueryBuilder();
    abfsUriQueryBuilder.addQuery(QUERY_PARAM_ACTION, APPEND_ACTION);
    abfsUriQueryBuilder.addQuery(QUERY_PARAM_POSITION, Long.toString(position));
    // AbfsInputStream/AbfsOutputStream reuse SAS tokens for better performance
    String sasTokenForReuse = appendSASTokenToQuery(path, SASTokenProvider.WRITE_OPERATION,
        abfsUriQueryBuilder, cachedSasToken);

    final URL url = createRequestUrl(path, abfsUriQueryBuilder.toString());
    final AbfsRestOperation op = new AbfsRestOperation(
        AbfsRestOperationType.Append,
            this,
            HTTP_METHOD_PUT,
            url,
            requestHeaders, buffer, offset, length, sasTokenForReuse);
    op.execute();
    return op;
  }

  public AbfsRestOperation flush(final String path, final long position, boolean retainUncommittedData,
                                 boolean isClose, final String cachedSasToken)
      throws AzureBlobFileSystemException {
    final List<AbfsHttpHeader> requestHeaders = createDefaultHeaders();
    // JDK7 does not support PATCH, so to workaround the issue we will use
    // PUT and specify the real method in the X-Http-Method-Override header.
    requestHeaders.add(new AbfsHttpHeader(X_HTTP_METHOD_OVERRIDE,
            HTTP_METHOD_PATCH));

    final AbfsUriQueryBuilder abfsUriQueryBuilder = createDefaultUriQueryBuilder();
    abfsUriQueryBuilder.addQuery(QUERY_PARAM_ACTION, FLUSH_ACTION);
    abfsUriQueryBuilder.addQuery(QUERY_PARAM_POSITION, Long.toString(position));
    abfsUriQueryBuilder.addQuery(QUERY_PARAM_RETAIN_UNCOMMITTED_DATA, String.valueOf(retainUncommittedData));
    abfsUriQueryBuilder.addQuery(QUERY_PARAM_CLOSE, String.valueOf(isClose));
    // AbfsInputStream/AbfsOutputStream reuse SAS tokens for better performance
    String sasTokenForReuse = appendSASTokenToQuery(path, SASTokenProvider.WRITE_OPERATION,
        abfsUriQueryBuilder, cachedSasToken);

    final URL url = createRequestUrl(path, abfsUriQueryBuilder.toString());
    final AbfsRestOperation op = new AbfsRestOperation(
            AbfsRestOperationType.Flush,
            this,
            HTTP_METHOD_PUT,
            url,
            requestHeaders, sasTokenForReuse);
    op.execute();
    return op;
  }

  public AbfsRestOperation setPathProperties(final String path, final String properties)
      throws AzureBlobFileSystemException {
    final List<AbfsHttpHeader> requestHeaders = createDefaultHeaders();
    // JDK7 does not support PATCH, so to workaround the issue we will use
    // PUT and specify the real method in the X-Http-Method-Override header.
    requestHeaders.add(new AbfsHttpHeader(X_HTTP_METHOD_OVERRIDE,
            HTTP_METHOD_PATCH));

    requestHeaders.add(new AbfsHttpHeader(X_MS_PROPERTIES, properties));

    final AbfsUriQueryBuilder abfsUriQueryBuilder = createDefaultUriQueryBuilder();
    abfsUriQueryBuilder.addQuery(QUERY_PARAM_ACTION, SET_PROPERTIES_ACTION);
    appendSASTokenToQuery(path, SASTokenProvider.SET_PROPERTIES_OPERATION, abfsUriQueryBuilder);

    final URL url = createRequestUrl(path, abfsUriQueryBuilder.toString());
    final AbfsRestOperation op = new AbfsRestOperation(
            AbfsRestOperationType.SetPathProperties,
            this,
            HTTP_METHOD_PUT,
            url,
            requestHeaders);
    op.execute();
    return op;
  }

  public AbfsRestOperation getPathStatus(final String path, final boolean includeProperties) throws AzureBlobFileSystemException {
    final List<AbfsHttpHeader> requestHeaders = createDefaultHeaders();

    final AbfsUriQueryBuilder abfsUriQueryBuilder = createDefaultUriQueryBuilder();
    String operation = SASTokenProvider.GET_PROPERTIES_OPERATION;
    if (!includeProperties) {
      // The default action (operation) is implicitly to get properties and this action requires read permission
      // because it reads user defined properties.  If the action is getStatus or getAclStatus, then
      // only traversal (execute) permission is required.
      abfsUriQueryBuilder.addQuery(HttpQueryParams.QUERY_PARAM_ACTION, AbfsHttpConstants.GET_STATUS);
      operation = SASTokenProvider.GET_STATUS_OPERATION;
    }
    abfsUriQueryBuilder.addQuery(HttpQueryParams.QUERY_PARAM_UPN, String.valueOf(abfsConfiguration.isUpnUsed()));
    appendSASTokenToQuery(path, operation, abfsUriQueryBuilder);

    final URL url = createRequestUrl(path, abfsUriQueryBuilder.toString());
    final AbfsRestOperation op = new AbfsRestOperation(
            AbfsRestOperationType.GetPathStatus,
            this,
            HTTP_METHOD_HEAD,
            url,
            requestHeaders);
    op.execute();
    return op;
  }

  public AbfsRestOperation read(final String path, final long position, final byte[] buffer, final int bufferOffset,
                                final int bufferLength, final String eTag, String cachedSasToken) throws AzureBlobFileSystemException {
    final List<AbfsHttpHeader> requestHeaders = createDefaultHeaders();
    requestHeaders.add(new AbfsHttpHeader(RANGE,
            String.format("bytes=%d-%d", position, position + bufferLength - 1)));
    requestHeaders.add(new AbfsHttpHeader(IF_MATCH, eTag));

    final AbfsUriQueryBuilder abfsUriQueryBuilder = createDefaultUriQueryBuilder();
    // AbfsInputStream/AbfsOutputStream reuse SAS tokens for better performance
    String sasTokenForReuse = appendSASTokenToQuery(path, SASTokenProvider.READ_OPERATION,
        abfsUriQueryBuilder, cachedSasToken);

    final URL url = createRequestUrl(path, abfsUriQueryBuilder.toString());

    final AbfsRestOperation op = new AbfsRestOperation(
            AbfsRestOperationType.ReadFile,
            this,
            HTTP_METHOD_GET,
            url,
            requestHeaders,
            buffer,
            bufferOffset,
            bufferLength, sasTokenForReuse);
    op.execute();

    return op;
  }

  public AbfsRestOperation deletePath(final String path, final boolean recursive, final String continuation)
          throws AzureBlobFileSystemException {
    final List<AbfsHttpHeader> requestHeaders = createDefaultHeaders();

    final AbfsUriQueryBuilder abfsUriQueryBuilder = createDefaultUriQueryBuilder();
    abfsUriQueryBuilder.addQuery(QUERY_PARAM_RECURSIVE, String.valueOf(recursive));
    abfsUriQueryBuilder.addQuery(QUERY_PARAM_CONTINUATION, continuation);
    String operation = recursive ? SASTokenProvider.DELETE_RECURSIVE_OPERATION : SASTokenProvider.DELETE_OPERATION;
    appendSASTokenToQuery(path, operation, abfsUriQueryBuilder);

    final URL url = createRequestUrl(path, abfsUriQueryBuilder.toString());
    final AbfsRestOperation op = new AbfsRestOperation(
            AbfsRestOperationType.DeletePath,
            this,
            HTTP_METHOD_DELETE,
            url,
            requestHeaders);
    op.execute();
    return op;
  }

  public AbfsRestOperation setOwner(final String path, final String owner, final String group)
      throws AzureBlobFileSystemException {
    final List<AbfsHttpHeader> requestHeaders = createDefaultHeaders();
    // JDK7 does not support PATCH, so to workaround the issue we will use
    // PUT and specify the real method in the X-Http-Method-Override header.
    requestHeaders.add(new AbfsHttpHeader(X_HTTP_METHOD_OVERRIDE,
            HTTP_METHOD_PATCH));

    if (owner != null && !owner.isEmpty()) {
      requestHeaders.add(new AbfsHttpHeader(HttpHeaderConfigurations.X_MS_OWNER, owner));
    }
    if (group != null && !group.isEmpty()) {
      requestHeaders.add(new AbfsHttpHeader(HttpHeaderConfigurations.X_MS_GROUP, group));
    }

    final AbfsUriQueryBuilder abfsUriQueryBuilder = createDefaultUriQueryBuilder();
    abfsUriQueryBuilder.addQuery(HttpQueryParams.QUERY_PARAM_ACTION, AbfsHttpConstants.SET_ACCESS_CONTROL);
    appendSASTokenToQuery(path, SASTokenProvider.SET_OWNER_OPERATION, abfsUriQueryBuilder);

    final URL url = createRequestUrl(path, abfsUriQueryBuilder.toString());
    final AbfsRestOperation op = new AbfsRestOperation(
        AbfsRestOperationType.SetOwner,
        this,
        AbfsHttpConstants.HTTP_METHOD_PUT,
        url,
        requestHeaders);
    op.execute();
    return op;
  }

  public AbfsRestOperation setPermission(final String path, final String permission)
      throws AzureBlobFileSystemException {
    final List<AbfsHttpHeader> requestHeaders = createDefaultHeaders();
    // JDK7 does not support PATCH, so to workaround the issue we will use
    // PUT and specify the real method in the X-Http-Method-Override header.
    requestHeaders.add(new AbfsHttpHeader(X_HTTP_METHOD_OVERRIDE,
            HTTP_METHOD_PATCH));

    requestHeaders.add(new AbfsHttpHeader(HttpHeaderConfigurations.X_MS_PERMISSIONS, permission));

    final AbfsUriQueryBuilder abfsUriQueryBuilder = createDefaultUriQueryBuilder();
    abfsUriQueryBuilder.addQuery(HttpQueryParams.QUERY_PARAM_ACTION, AbfsHttpConstants.SET_ACCESS_CONTROL);
    appendSASTokenToQuery(path, SASTokenProvider.SET_PERMISSION_OPERATION, abfsUriQueryBuilder);

    final URL url = createRequestUrl(path, abfsUriQueryBuilder.toString());
    final AbfsRestOperation op = new AbfsRestOperation(
        AbfsRestOperationType.SetPermissions,
        this,
        AbfsHttpConstants.HTTP_METHOD_PUT,
        url,
        requestHeaders);
    op.execute();
    return op;
  }

  public AbfsRestOperation setAcl(final String path, final String aclSpecString) throws AzureBlobFileSystemException {
    return setAcl(path, aclSpecString, AbfsHttpConstants.EMPTY_STRING);
  }

  public AbfsRestOperation setAcl(final String path, final String aclSpecString, final String eTag)
      throws AzureBlobFileSystemException {
    final List<AbfsHttpHeader> requestHeaders = createDefaultHeaders();
    // JDK7 does not support PATCH, so to workaround the issue we will use
    // PUT and specify the real method in the X-Http-Method-Override header.
    requestHeaders.add(new AbfsHttpHeader(X_HTTP_METHOD_OVERRIDE,
            HTTP_METHOD_PATCH));

    requestHeaders.add(new AbfsHttpHeader(HttpHeaderConfigurations.X_MS_ACL, aclSpecString));

    if (eTag != null && !eTag.isEmpty()) {
      requestHeaders.add(new AbfsHttpHeader(HttpHeaderConfigurations.IF_MATCH, eTag));
    }

    final AbfsUriQueryBuilder abfsUriQueryBuilder = createDefaultUriQueryBuilder();
    abfsUriQueryBuilder.addQuery(HttpQueryParams.QUERY_PARAM_ACTION, AbfsHttpConstants.SET_ACCESS_CONTROL);
    appendSASTokenToQuery(path, SASTokenProvider.SET_ACL_OPERATION, abfsUriQueryBuilder);

    final URL url = createRequestUrl(path, abfsUriQueryBuilder.toString());
    final AbfsRestOperation op = new AbfsRestOperation(
        AbfsRestOperationType.SetAcl,
        this,
        AbfsHttpConstants.HTTP_METHOD_PUT,
        url,
        requestHeaders);
    op.execute();
    return op;
  }

  public AbfsRestOperation getAclStatus(final String path) throws AzureBlobFileSystemException {
    return getAclStatus(path, abfsConfiguration.isUpnUsed());
  }

  public AbfsRestOperation getAclStatus(final String path, final boolean useUPN) throws AzureBlobFileSystemException {
    final List<AbfsHttpHeader> requestHeaders = createDefaultHeaders();

    final AbfsUriQueryBuilder abfsUriQueryBuilder = createDefaultUriQueryBuilder();
    abfsUriQueryBuilder.addQuery(HttpQueryParams.QUERY_PARAM_ACTION, AbfsHttpConstants.GET_ACCESS_CONTROL);
    abfsUriQueryBuilder.addQuery(HttpQueryParams.QUERY_PARAM_UPN, String.valueOf(useUPN));
    appendSASTokenToQuery(path, SASTokenProvider.GET_ACL_OPERATION, abfsUriQueryBuilder);

    final URL url = createRequestUrl(path, abfsUriQueryBuilder.toString());
    final AbfsRestOperation op = new AbfsRestOperation(
        AbfsRestOperationType.GetAcl,
        this,
        AbfsHttpConstants.HTTP_METHOD_HEAD,
        url,
        requestHeaders);
    op.execute();
    return op;
  }

  /**
   * Talks to the server to check whether the permission specified in
   * the rwx parameter is present for the path specified in the path parameter.
   *
   * @param path  Path for which access check needs to be performed
   * @param rwx   The permission to be checked on the path
   * @return      The {@link AbfsRestOperation} object for the operation
   * @throws AzureBlobFileSystemException in case of bad requests
   */
  public AbfsRestOperation checkAccess(String path, String rwx)
      throws AzureBlobFileSystemException {
    AbfsUriQueryBuilder abfsUriQueryBuilder = createDefaultUriQueryBuilder();
    abfsUriQueryBuilder.addQuery(QUERY_PARAM_ACTION, CHECK_ACCESS);
    abfsUriQueryBuilder.addQuery(QUERY_FS_ACTION, rwx);
    appendSASTokenToQuery(path, SASTokenProvider.CHECK_ACCESS_OPERATION, abfsUriQueryBuilder);
    URL url = createRequestUrl(path, abfsUriQueryBuilder.toString());
    AbfsRestOperation op = new AbfsRestOperation(
        AbfsRestOperationType.CheckAccess, this,
        AbfsHttpConstants.HTTP_METHOD_HEAD, url, createDefaultHeaders());
    op.execute();
    return op;
  }

  /**
   * Get the directory query parameter used by the List Paths REST API and used
   * as the path in the continuation token.  If the input path is null or the
   * root path "/", empty string is returned. If the input path begins with '/',
   * the return value is the substring beginning at offset 1.  Otherwise, the
   * input path is returned.
   * @param path the path to be listed.
   * @return the value of the directory query parameter
   */
  public static String getDirectoryQueryParameter(final String path) {
    String directory = path;
    if (Strings.isNullOrEmpty(directory)) {
      directory = AbfsHttpConstants.EMPTY_STRING;
    } else if (directory.charAt(0) == '/') {
      directory = directory.substring(1);
    }
    return directory;
  }

  /**
   * If configured for SAS AuthType, appends SAS token to queryBuilder
   * @param path
   * @param operation
   * @param queryBuilder
   * @return sasToken - returned for optional re-use.
   * @throws SASTokenProviderException
   */
  private String appendSASTokenToQuery(String path, String operation, AbfsUriQueryBuilder queryBuilder) throws SASTokenProviderException {
    return appendSASTokenToQuery(path, operation, queryBuilder, null);
  }

  /**
   * If configured for SAS AuthType, appends SAS token to queryBuilder
   * @param path
   * @param operation
   * @param queryBuilder
   * @param cachedSasToken - previously acquired SAS token to be reused.
   * @return sasToken - returned for optional re-use.
   * @throws SASTokenProviderException
   */
  private String appendSASTokenToQuery(String path,
                                       String operation,
                                       AbfsUriQueryBuilder queryBuilder,
                                       String cachedSasToken)
      throws SASTokenProviderException {
    String sasToken = null;
    if (this.authType == AuthType.SAS) {
      try {
        LOG.trace("Fetch SAS token for {} on {}", operation, path);
        if (cachedSasToken == null) {
          sasToken = sasTokenProvider.getSASToken(this.accountName,
              this.filesystem, path, operation);
          if ((sasToken == null) || sasToken.isEmpty()) {
            throw new UnsupportedOperationException("SASToken received is empty or null");
          }
        } else {
          sasToken = cachedSasToken;
          LOG.trace("Using cached SAS token.");
        }
        queryBuilder.setSASToken(sasToken);
        LOG.trace("SAS token fetch complete for {} on {}", operation, path);
      } catch (Exception ex) {
        throw new SASTokenProviderException(String.format("Failed to acquire a SAS token for %s on %s due to %s",
            operation,
            path,
            ex.toString()));
      }
    }
    return sasToken;
  }

  private URL createRequestUrl(final String query) throws AzureBlobFileSystemException {
    return createRequestUrl(EMPTY_STRING, query);
  }

  private URL createRequestUrl(final String path, final String query)
          throws AzureBlobFileSystemException {
    final String base = baseUrl.toString();
    String encodedPath = path;
    try {
      encodedPath = urlEncode(path);
    } catch (AzureBlobFileSystemException ex) {
      LOG.debug("Unexpected error.", ex);
      throw new InvalidUriException(path);
    }

    final StringBuilder sb = new StringBuilder();
    sb.append(base);
    sb.append(encodedPath);
    sb.append(query);

    final URL url;
    try {
      url = new URL(sb.toString());
    } catch (MalformedURLException ex) {
      throw new InvalidUriException(sb.toString());
    }
    return url;
  }

  public static String urlEncode(final String value) throws AzureBlobFileSystemException {
    String encodedString;
    try {
      encodedString =  URLEncoder.encode(value, UTF_8)
          .replace(PLUS, PLUS_ENCODE)
          .replace(FORWARD_SLASH_ENCODE, FORWARD_SLASH);
    } catch (UnsupportedEncodingException ex) {
        throw new InvalidUriException(value);
    }

    return encodedString;
  }

  public synchronized String getAccessToken() throws IOException {
    if (tokenProvider != null) {
      return "Bearer " + tokenProvider.getToken().getAccessToken();
    } else {
      return null;
    }
  }

  public AuthType getAuthType() {
    return authType;
  }

  @VisibleForTesting
  String initializeUserAgent(final AbfsConfiguration abfsConfiguration,
      final String sslProviderName) {

    StringBuilder sb = new StringBuilder();

    sb.append(APN_VERSION);
    sb.append(SINGLE_WHITE_SPACE);
    sb.append(CLIENT_VERSION);
    sb.append(SINGLE_WHITE_SPACE);

    sb.append("(");

    sb.append(System.getProperty(JAVA_VENDOR)
        .replaceAll(SINGLE_WHITE_SPACE, EMPTY_STRING));
    sb.append(SINGLE_WHITE_SPACE);
    sb.append("JavaJRE");
    sb.append(SINGLE_WHITE_SPACE);
    sb.append(System.getProperty(JAVA_VERSION));
    sb.append(SEMICOLON);
    sb.append(SINGLE_WHITE_SPACE);

    sb.append(System.getProperty(OS_NAME)
        .replaceAll(SINGLE_WHITE_SPACE, EMPTY_STRING));
    sb.append(SINGLE_WHITE_SPACE);
    sb.append(System.getProperty(OS_VERSION));
    sb.append(FORWARD_SLASH);
    sb.append(System.getProperty(OS_ARCH));
    sb.append(SEMICOLON);

    appendIfNotEmpty(sb, sslProviderName, true);
    appendIfNotEmpty(sb,
        ExtensionHelper.getUserAgentSuffix(tokenProvider, EMPTY_STRING), true);

    sb.append(SINGLE_WHITE_SPACE);
    sb.append(abfsConfiguration.getClusterName());
    sb.append(FORWARD_SLASH);
    sb.append(abfsConfiguration.getClusterType());

    sb.append(")");

    appendIfNotEmpty(sb, abfsConfiguration.getCustomUserAgentPrefix(), false);

    return String.format(Locale.ROOT, sb.toString());
  }

  private void appendIfNotEmpty(StringBuilder sb, String regEx,
      boolean shouldAppendSemiColon) {
    if (regEx == null || regEx.trim().isEmpty()) {
      return;
    }
    sb.append(SINGLE_WHITE_SPACE);
    sb.append(regEx);
    if (shouldAppendSemiColon) {
      sb.append(SEMICOLON);
    }
  }

  @VisibleForTesting
  URL getBaseUrl() {
    return baseUrl;
  }

  @VisibleForTesting
  public SASTokenProvider getSasTokenProvider() {
    return this.sasTokenProvider;
  }
}
