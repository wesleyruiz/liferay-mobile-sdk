/**
 * Copyright (c) 2000-present Liferay, Inc. All rights reserved.
 *
 * This library is free software; you can redistribute it and/or modify it under
 * the terms of the GNU Lesser General Public License as published by the Free
 * Software Foundation; either version 2.1 of the License, or (at your option)
 * any later version.
 *
 * This library is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License for more
 * details.
 */

package com.liferay.mobile.android.http;

import com.liferay.mobile.android.auth.Authentication;
import com.liferay.mobile.android.auth.basic.DigestAuthentication;
import com.liferay.mobile.android.exception.RedirectException;
import com.liferay.mobile.android.exception.ServerException;
import com.liferay.mobile.android.service.Session;
import com.liferay.mobile.android.util.Validator;

import java.io.IOException;

import java.net.URI;

import org.apache.http.HttpEntity;
import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.ProtocolException;
import org.apache.http.client.HttpClient;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpGetHC4;
import org.apache.http.client.methods.HttpPostHC4;
import org.apache.http.entity.StringEntityHC4;
import org.apache.http.impl.client.DefaultRedirectStrategy;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.protocol.BasicHttpContext;
import org.apache.http.protocol.HttpContext;
import org.apache.http.util.EntityUtils;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * @author Bruno Farache
 * @author Silvio Santos
 */
public class HttpUtil {

	public static final String IF_MODIFIED_SINCE = "If-Modified-Since";

	public static final String JSONWS_PATH_61 = "api/secure/jsonws";

	public static final String JSONWS_PATH_62 = "api/jsonws";

	public static final String LAST_MODIFIED = "Last-Modified";

	public static void checkStatusCode(
			HttpRequest request, HttpResponse response)
		throws ServerException {

		int status = response.getStatusLine().getStatusCode();

		if ((status == HttpStatus.SC_MOVED_PERMANENTLY) ||
			(status == HttpStatus.SC_MOVED_TEMPORARILY) ||
			(status == HttpStatus.SC_SEE_OTHER) ||
			(status == HttpStatus.SC_TEMPORARY_REDIRECT)) {

			throw new RedirectException(getRedirectUrl(request, response));
		}

		if (status == HttpStatus.SC_UNAUTHORIZED) {
			throw new ServerException("Authentication failed.");
		}

		if (status != HttpStatus.SC_OK) {
			throw new ServerException(
				"Request failed. Response code: " + status);
		}
	}

	public static HttpClient getClient(Session session) {
		return getClientBuilder(session).build();
	}

	public static HttpClientBuilder getClientBuilder(Session session) {
		HttpClientBuilder clientBuilder = HttpClientBuilder.create();

		RequestConfig.Builder requestBuilder = RequestConfig.custom();
		int timeout = session.getConnectionTimeout();
		requestBuilder = requestBuilder.setConnectTimeout(timeout);
		requestBuilder = requestBuilder.setConnectionRequestTimeout(timeout);

		clientBuilder.setDefaultRequestConfig(requestBuilder.build());
		clientBuilder.setRedirectStrategy(new DefaultRedirectStrategy() {

			@Override
			protected boolean isRedirectable(String method) {
				return false;
			}

		});

		Authentication auth = session.getAuthentication();

		if ((auth != null) && (auth instanceof DigestAuthentication)) {
			DigestAuthentication digest = (DigestAuthentication)auth;
			digest.authenticate(clientBuilder);
		}

		return clientBuilder;
	}

	public static HttpGetHC4 getHttpGetHC4(Session session, String URL)
		throws Exception {

		HttpGetHC4 httpGet = new HttpGetHC4(URL);

		authenticate(session, httpGet);

		return httpGet;
	}

	public static HttpPostHC4 getHttpPostHC4(Session session, String URL)
		throws Exception {

		HttpPostHC4 httpPost = new HttpPostHC4(URL);

		authenticate(session, httpPost);

		return httpPost;
	}

	public static String getResponseString(HttpResponse response)
		throws IOException {

		HttpEntity entity = response.getEntity();

		if (entity == null) {
			return null;
		}

		return EntityUtils.toString(entity);
	}

	public static String getURL(Session session, String path) {
		StringBuilder sb = new StringBuilder();

		String server = session.getServer();

		sb.append(server);

		if (!server.endsWith("/")) {
			sb.append("/");
		}

		sb.append(_JSONWS_PATH);
		sb.append(path);

		return sb.toString();
	}

	public static void handleServerError(
			HttpRequest request, HttpResponse response, String json)
		throws ServerException {

		checkStatusCode(request, response);
		handlePortalException(json);
	}

	public static JSONArray post(Session session, JSONArray commands)
		throws Exception {

		HttpClient client = getClient(session);
		HttpPostHC4 request = getHttpPostHC4(
			session, getURL(session, "/invoke"));

		request.setEntity(new StringEntityHC4(commands.toString(), "UTF-8"));

		HttpResponse response = client.execute(request);
		String json = HttpUtil.getResponseString(response);

		handleServerError(request, response, json);

		return new JSONArray(json);
	}

	public static JSONArray post(Session session, JSONObject command)
		throws Exception {

		JSONArray commands = new JSONArray();
		commands.put(command);

		return post(session, commands);
	}

	@SuppressWarnings("unused")
	public static void setJSONWSPath(String jsonwsPath) {
		_JSONWS_PATH = jsonwsPath;
	}

	protected static void authenticate(Session session, HttpRequest request)
		throws Exception {

		Authentication auth = session.getAuthentication();

		if (auth != null) {
			auth.authenticate(request);
		}
	}

	protected static String getRedirectUrl(
			HttpRequest request, HttpResponse response)
		throws ServerException {

		try {
			DefaultRedirectStrategy redirect = new DefaultRedirectStrategy();
			HttpContext context = new BasicHttpContext();

			URI uri = redirect.getLocationURI(request, response, context);
			String url = uri.toString();

			if (url.endsWith("/")) {
				url = url.substring(0, url.length() - 1);
			}

			return url;
		}
		catch (ProtocolException pe) {
			throw new ServerException(pe);
		}
	}

	protected static void handlePortalException(String json)
		throws ServerException {

		try {
			if (isJSONObject(json)) {
				JSONObject jsonObj = new JSONObject(json);

				if (jsonObj.has("exception")) {
					String message = jsonObj.getString("exception");
					String detail = jsonObj.optString("message", null);

					JSONObject error = jsonObj.optJSONObject("error");

					if (error != null) {
						message = error.getString("type");
						detail = error.getString("message");
					}

					throw new ServerException(message, detail);
				}
			}
		}
		catch (JSONException je) {
			throw new ServerException(je);
		}
	}

	protected static boolean isJSONObject(String json) {
		if (Validator.isNotNull(json) && json.startsWith("{")) {
			return true;
		}

		return false;
	}

	private static String _JSONWS_PATH = JSONWS_PATH_62;

}