package com.example.httpapp;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A minimal, fully-implemented {@code HttpURLConnection} subclass — real production targets are
 * {@code sun.net.www.protocol.http.HttpURLConnection} (see {@code
 * io.castellan.apm.agent.weave.http.HttpUrlConnectionInstrumentationRule}'s javadoc for why that
 * exact class can't be exercised by a direct {@code transform()} unit test), but the *shape* being
 * matched — a direct {@code extends java.net.HttpURLConnection} with an overridden no-arg {@code
 * connect()} — is identical here.
 */
public final class FakeHttpUrlConnection extends HttpURLConnection {

    public boolean connectCalled;
    public boolean throwOnConnect;
    public final Map<String, String> capturedRequestProperties = new LinkedHashMap<>();

    public FakeHttpUrlConnection(URL url) {
        super(url);
    }

    @Override
    public void connect() {
        connectCalled = true;
        if (throwOnConnect) {
            throw new IllegalStateException("connection refused");
        }
    }

    @Override
    public void disconnect() {
    }

    @Override
    public boolean usingProxy() {
        return false;
    }

    @Override
    public void setRequestProperty(String key, String value) {
        capturedRequestProperties.put(key, value);
        super.setRequestProperty(key, value);
    }
}
