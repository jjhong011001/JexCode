package com.jexcode.browser;

public interface BrowserConnector {
    String status();

    String connectDefault();

    String disconnect();
}
