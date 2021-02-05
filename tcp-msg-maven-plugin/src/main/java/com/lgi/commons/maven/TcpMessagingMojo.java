/*
 * If not stated otherwise in this file or this component's LICENSE file the
 * following copyright and licenses apply:
 *
 * Copyright 2021 Liberty Global Technology Services BV
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.lgi.commons.maven;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.stream.IntStream;


/**
 *  Base Mojo for TCP messaging plug-in. It is intended to be able to send String messages into TCP socked during maven build process.
 */
@Mojo(name = "tcpmsg")
public class TcpMessagingMojo extends AbstractMojo {
    private static final String LOG_PREFIX = "maven-tcpmsg";

    /**
     * Host where the messages will be sent.
     */
    @Parameter(property = "tcpmsg.host", defaultValue = "localhost")
    private String host;

    /**
     * The total number of message sending attempts.
     */
    @Parameter(property = "tcpmsg.repeatAmount", defaultValue = "1")
    private Integer repeatAmount;

    /**
     * Interval in seconds between sending consecutive messages.
     */
    @Parameter(property = "tcpmsg.intervalSec", defaultValue = "5")
    private Integer intervalSec;

    /**
     * Port where the messages will be sent. Mandatory parameter.
     */
    @Parameter(property = "tcpmsg.port")
    private Integer port;

    /**
     * Message content to send.
     */
    @Parameter(property = "tcpmsg.msg")
    private String msg;

    /**
     * Maven execution hook for sending TCP messages.
     * @throws MojoExecutionException in case port is not specified.
     */
    @Override
    public void execute() throws MojoExecutionException {
        if (port == null) {
            getLog().error("Please specify 'port' param. of TCP socket.");
            throw new MojoExecutionException(getPluginContext().keySet().toString());
        } else if (msg == null) {
            getLog().warn("Please specify 'msg' param. with message to send.");
        } else {
            doCommunicate();
        }
    }

    /**
     * Initiates communication thread.
     */
    private void doCommunicate() {
        Thread communicationChannel = new Thread(
                new ThreadGroup(LOG_PREFIX),
                this::sendMessages,
                String.format("%s-send", LOG_PREFIX)
        );
        communicationChannel.setDaemon(true);
        communicationChannel.start();
    }

    /**
     * Sends preconfigured amount of messages.
     */
    private void sendMessages() {
        IntStream.range(0, repeatAmount).forEach(attempt -> {
            sleepForConfiguredInterval();
            getLog().info(String.format("%s: Attempting [%d] to send message '%s' to TCP socket on %s:%d", LOG_PREFIX, attempt, msg, host, port));
            handleSendAttempt();
        });
    }

    /**
     * Sleep for preconfigured interval.
     */
    private void sleepForConfiguredInterval() {
        try {
            Thread.sleep(intervalSec * 1000);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    /**
     * Handles opening and closing socket together with appropriate readers writers.
     * Sends the message, then reads and prints received response.
     */
    private void handleSendAttempt() {
        try (Socket clientSocket = new Socket(host, port)) {
            try (PrintWriter out = new PrintWriter(clientSocket.getOutputStream(), true)) {
                try (BufferedReader in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()))) {
                    String response = communicate(in, out);
                    getLog().info(String.format("%s: Received: %s", LOG_PREFIX, response));
                }
            }
        } catch (IOException e) {
            logSocketIssue(e);
        }
    }

    /**
     * IO streams read and write.
     * @param in input stream to read response from.
     * @param out output stream to write message to.
     * @return response or null if nothing was received.
     */
    private String communicate(BufferedReader in, PrintWriter out) {
        try {
            out.println(msg);
            return in.readLine();
        } catch (IOException e) {
            String errorMsg = String.format("%s: Issue with sending message '%s' to TCP socket on %s:%d: %s - %s", LOG_PREFIX, msg, host, port, e.getClass().getSimpleName(), e.getMessage());
            getLog().error(errorMsg);
            return null;
        }
    }

    /**
     * Wrapper for logging socket related exception information.
     * @param exception any exception caught due to socket connection handling.
     */
    private void logSocketIssue(Exception exception) {
        String warnMsg = String.format("%s: Issue with connection to TCP socket on %s:%d - %s: %s", LOG_PREFIX, host, port, exception.getClass().getSimpleName(), exception.getMessage());
        getLog().warn(warnMsg);
    }
}
