/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.tools.profiler.support.profilerserver;

import android.content.Context;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.net.TrafficStats;
import android.os.SystemClock;
import android.util.Log;

import com.android.tools.profiler.support.profilers.*;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static com.android.tools.profiler.support.profilerserver.MessageHeader.REQUEST_FLAGS;
import static com.android.tools.profiler.support.profilerserver.MessageHeader.RESPONSE_MASK;

/**
 * ProfilerServer is the main server service for the studio profiling feature.
 * Clients (usual Android Studio) make connections to the server in order to
 * retrieve profiling information about the underlying application.
 *
 * The server is a singleton, and is designed such that the {@link #start()}
 * and {@link #stop()} methods are reentrant.
 */
@SuppressWarnings("ForLoopReplaceableByForEach")
public class ProfilerServer implements Runnable {
    public static final String SERVER_NAME = "StudioProfiler";

    private static final long HEARTBEAT_INTERVAL = TimeUnit.NANOSECONDS.convert(2L, TimeUnit.SECONDS);
    private static final long HEARTBEAT_TIMEOUT = TimeUnit.NANOSECONDS.convert(5L, TimeUnit.SECONDS);
    private static final int INPUT_ARRAY_SIZE = 1024;
    private static final int OUTPUT_ARRAY_SIZE = 64 * 1024;

    private static final ProfilerServer INSTANCE = new ProfilerServer();

    private static final long CONNECTION_RETRY_TIME_MS = 100L;
    private static final long SERVER_UPDATE_TIME_NS = TimeUnit.NANOSECONDS.convert(1L, TimeUnit.SECONDS) / 60L; // 60FPS

    private static final int VERSION = 0;

    private static final short SUBTYPE_HANDSHAKE = 0;
    private static final short SUBTYPE_PING = 1;
    private static final short SUBTYPE_RESERVED = 2;
    private static final short SUBTYPE_ENABLE_BITS = 3;

    private static final int HANDSHAKE_HEADER_LENGTH = 16;

    private static final byte OK_RESPONSE = (byte)0;
    private static final byte ERROR_RESPONSE = (byte)1;
    private static final byte STRING_TERMINATOR = (byte)0;

    private static final int THREAD_STATS_TAG = 0xFFFFFEFF;

    private static final String UNIX_SOCKET_NAME = "StudioProfiler_pid" + android.os.Process.myPid();

    private volatile Context mContext;

    private final ServerComponent mServerComponent = new ServerComponent();
    private final List<ProfilerComponent> mRegisteredComponents = new ArrayList<ProfilerComponent>(ProfilerRegistry.TOTAL);
    private final MessageHeader mInputMessageHeader = new MessageHeader();
    private final MessageHeader mOutputMessageHeader = new MessageHeader();
    private final byte[] mInputArray = new byte[INPUT_ARRAY_SIZE];
    private final byte[] mOutputArray = new byte[OUTPUT_ARRAY_SIZE];
    private final ByteBuffer mInputBuffer = ByteBuffer.wrap(mInputArray).order(ByteOrder.LITTLE_ENDIAN);
    private final ByteBuffer mOutputBuffer = ByteBuffer.wrap(mOutputArray).order(ByteOrder.LITTLE_ENDIAN);

    private volatile Thread mServerThread;
    private LocalServerSocket ourServerSocket;
    private CountDownLatch mContinueRunningLatch;
    private long mLastHeartbeatTime = 0;
    private short mHeartbeat = 0;
    private short mOutstandingHeartbeat = mHeartbeat;
    private boolean mDoHeartBeat = false;

    public static ProfilerServer getInstance() {
        return INSTANCE;
    }

    private ProfilerServer() {
        registerComponent(mServerComponent);
        registerComponent(new MemoryProfiler());
        registerComponent(new NetworkProfiler());
    }

    /**
     * Starts the singleton App Server as a separate thread.
     */
    public synchronized void start() throws IOException {
        if (mServerThread == null) {
            Log.v(SERVER_NAME, "Starting advanced profiling server");
            ourServerSocket = new LocalServerSocket(UNIX_SOCKET_NAME);
            INSTANCE.mContinueRunningLatch = new CountDownLatch(1);
            mServerThread = new Thread(INSTANCE, SERVER_NAME);
            mServerThread.start();
            Log.v(SERVER_NAME, "Advanced profiling server started");
        }
    }

    /**
     * Stops and joins the singleton server.
     */
    public synchronized void stop() throws IOException {
        if (mServerThread != null) {
            Log.v(SERVER_NAME, "Stopping advanced profiling server");
            INSTANCE.mContinueRunningLatch.countDown();
            mServerThread.interrupt();
            try {
                mServerThread.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                Log.v(SERVER_NAME, Log.getStackTraceString(e));
            }
            mServerThread = null;
            ourServerSocket.close();
            ourServerSocket = null;
            Log.v(SERVER_NAME, "Advanced profiling server stopped");
        }
    }

    /**
     * Registers a component with the server to listen for events.
     */
    public void registerComponent(ProfilerComponent profilerComponent) {
        byte componentId = profilerComponent.getComponentId();
        if (componentId < 0 || componentId >= ProfilerRegistry.TOTAL) {
            throw new IllegalArgumentException("Component with ID " + componentId + " is invalid");
        }

        for (int i = 0; i < mRegisteredComponents.size(); ++i) {
            if (mRegisteredComponents.get(i).getComponentId() == componentId) {
                throw new IllegalArgumentException("Component with ID " + componentId + " has already been registered");
            }
        }
        mRegisteredComponents.add(profilerComponent);
        Collections.sort(mRegisteredComponents);
    }

    public void initialize(Context context) {
        mContext = context;
        for (ProfilerComponent component : mRegisteredComponents) {
            component.initialize();
        }
    }

    public Context getContext() {
        return mContext;
    }

    @Override
    public void run() {
        TrafficStats.setThreadStatsTag(THREAD_STATS_TAG);
        Log.v(SERVER_NAME, "Advanced profiling server thread started");
        LocalSocket localSocket = null;

        while (mContinueRunningLatch.getCount() > 0) {
            try {
                Log.v(SERVER_NAME, "Advanced profiling server is listening for connection");
                localSocket = accept();
                if (localSocket == null) {
                    mContinueRunningLatch.countDown();
                    Log.v(SERVER_NAME, "Could not accept socket");
                    break;
                }

                mHeartbeat = 0;
                mOutstandingHeartbeat = mHeartbeat;
                mLastHeartbeatTime = SystemClock.elapsedRealtimeNanos();

                if (mServerComponent.processHandshake(mLastHeartbeatTime, localSocket) == ProfilerComponent.RESPONSE_RECONNECT) {
                    continue;
                }

                try {
                    for (int i = 0; i < mRegisteredComponents.size(); i++) {
                        mRegisteredComponents.get(i).onClientConnection();
                        flushBuffer(localSocket);
                    }
                } catch (IOException e) {
                    Log.e(SERVER_NAME, e.toString());
                    break;
                }

                while (mContinueRunningLatch.getCount() > 0) {
                    long startTime = SystemClock.elapsedRealtimeNanos();

                    try {
                        processMessages(startTime, localSocket);
                        updateComponents(startTime, localSocket);
                    }
                    catch (IOException e) {
                        Log.e(SERVER_NAME, Log.getStackTraceString(e));
                        break;
                    }

                    try {
                        long sleepTime = SERVER_UPDATE_TIME_NS - (SystemClock.elapsedRealtimeNanos() - startTime);
                        if (sleepTime > 0) {
                            mContinueRunningLatch.await(sleepTime, TimeUnit.NANOSECONDS);
                        }
                    }
                    catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }

                for (int i = 0; i < mRegisteredComponents.size(); i++) {
                    mRegisteredComponents.get(i).onClientDisconnection();
                }
            } catch (Exception e) {
                Log.e(SERVER_NAME, Log.getStackTraceString(e));
            } finally {
                if (localSocket != null) {
                    try {
                        localSocket.close();
                        Log.v(SERVER_NAME, "Reinitializing profiling server");
                    } catch (IOException ignored) {}
                    finally {
                        localSocket = null;
                    }
                }
            }
        }
        Log.v(SERVER_NAME, "Stopped profiling server");
        TrafficStats.clearThreadStatsTag();
    }

    private void flushBuffer(LocalSocket localSocket) throws IOException {
        mOutputBuffer.flip();
        if (mOutputBuffer.hasRemaining()) {
            localSocket.getOutputStream().write(mOutputArray, 0, mOutputBuffer.limit());
            Log.v(SERVER_NAME, "Wrote " + mOutputBuffer.limit() + " bytes");
        }
        mOutputBuffer.clear();
        localSocket.getOutputStream().flush();
    }

    private LocalSocket accept() {
        LocalSocket localSocket = null;
        try {
            while (mContinueRunningLatch.getCount() > 0 && localSocket == null) {
                localSocket = ourServerSocket.accept();
                if (localSocket == null) {
                    mContinueRunningLatch.await(CONNECTION_RETRY_TIME_MS, TimeUnit.MILLISECONDS);
                }
            }
            Log.v(SERVER_NAME, "Socket accepted");
            mInputBuffer.clear();
            mOutputBuffer.clear();
            return localSocket;
        } catch (IOException e) {
            Log.e(SERVER_NAME, e.toString());
        } catch (InterruptedException e) {
            Log.e(SERVER_NAME, e.toString());
            Thread.currentThread().interrupt();
        }
        return null;
    }

    private void processMessages(long frameStartTime, LocalSocket localSocket) throws IOException {
        if (localSocket.getInputStream().available() <= 0) {
            return;
        }

        int bytesRead = localSocket.getInputStream().read(mInputArray, mInputBuffer.position(), mInputBuffer.limit());
        if (bytesRead <= 0) {
            return;
        }

        mInputBuffer.position(mInputBuffer.position() + bytesRead);
        mInputBuffer.flip();

        while (mInputBuffer.remaining() >= MessageHeader.MESSAGE_HEADER_LENGTH) {
            mInputBuffer.mark();
            mInputMessageHeader.parseFromBuffer(mInputBuffer);
            if (mInputBuffer.remaining() < mInputMessageHeader.length - MessageHeader.MESSAGE_HEADER_LENGTH) {
                // Reset (but don't set the position of) the buffer so that
                // compacting will preserve the incomplete message.
                Log.v(SERVER_NAME, "Incomplete message");
                mInputBuffer.reset();
                break;
            }
            int payloadPosition = mInputBuffer.position();

            // Update the last heartbeat time to the current time.
            // We got a message from the client, so it means the client is still alive.
            mLastHeartbeatTime = frameStartTime;

            if (mInputMessageHeader.length < MessageHeader.MESSAGE_HEADER_LENGTH ||
                    mInputMessageHeader.length > mInputBuffer.capacity()) {
                throw new RuntimeException("Invalid length in message.");
            }

            Log.v(SERVER_NAME, "Processing message");
            for (int i = 0; i < mRegisteredComponents.size(); i++) {
                try {
                    mRegisteredComponents.get(i).receiveMessage(
                            frameStartTime, mInputMessageHeader, mInputBuffer, mOutputBuffer);
                }
                catch (Exception e) {
                    Log.e("ProfileServer", Log.getStackTraceString(e));
                }
                flushBuffer(localSocket);
                mInputBuffer.position(payloadPosition);
            }

            Log.v(SERVER_NAME, "Processed message");
            mInputBuffer.reset();
            // Manually advance the position past the end of the message.
            mInputBuffer.position(mInputBuffer.position() + mInputMessageHeader.length);
        }
        mInputBuffer.compact();
    }

    private void updateComponents(long frameStartTime, LocalSocket localSocket) throws IOException {
        int updatesRequired;
        do {
            updatesRequired = 0;

            for (int i = 0; i < mRegisteredComponents.size(); i++) {
                try {
                    ProfilerComponent component = mRegisteredComponents.get(i);
                    int result = component.update(frameStartTime, mOutputBuffer);
                    if (result == ProfilerComponent.UPDATE_ERROR_RECONNECT) {
                        throw new IOException("Error encountered when updating component with ID " + component.getComponentId());
                    }
                    updatesRequired += result;
                    flushBuffer(localSocket);
                }
                catch (RuntimeException e) {
                    Log.e(SERVER_NAME, Log.getStackTraceString(e));
                }
            }
        } while (updatesRequired > 0);
    }

    private class ServerComponent extends AbstractProfilerComponent {
        @Override
        public byte getComponentId() {
            return ProfilerRegistry.SERVER;
        }

        @Override
        public String configure(byte flags) {
            return null;
        }

        @Override
        public void onClientConnection() {}

        @Override
        public void onClientDisconnection() {}

        @Override
        public void initialize() {}

        @Override
        public int receiveMessage(long frameStartTime, MessageHeader header, ByteBuffer input, ByteBuffer output) {
            if (mInputMessageHeader.type != ProfilerRegistry.SERVER) {
                return RESPONSE_OK;
            }

            switch (mInputMessageHeader.subType) {
                case SUBTYPE_PING:
                    // Process heartbeat.
                    if ((mInputMessageHeader.flags & RESPONSE_MASK) == 0) {
                        mOutputMessageHeader.copyFrom(mInputMessageHeader);
                        mOutputMessageHeader.flags |= RESPONSE_MASK;
                        mOutputMessageHeader.writeToBuffer(output);
                        Log.v(SERVER_NAME, "Pong");
                    } else if ((mInputMessageHeader.flags & RESPONSE_MASK) == RESPONSE_MASK) {
                        if (mInputMessageHeader.id == mOutstandingHeartbeat) {
                            mOutstandingHeartbeat = mHeartbeat;
                        }
                    }
                    break;
                case SUBTYPE_RESERVED:
                    // TODO TBD
                    assert false;
                    break;
                case SUBTYPE_ENABLE_BITS: // Process enable bits.
                    byte targetComponent = input.get();
                    byte flags = input.get();
                    String result = null;

                    for (int i = 0; i < mRegisteredComponents.size(); i++) {
                        ProfilerComponent component = mRegisteredComponents.get(i);
                        if (component.getComponentId() == targetComponent) {
                            result = component.configure(flags);
                            break;
                        }
                    }
                    if (result != null && result.isEmpty()) {
                        throw new AssertionError("Empty result returned by component ID: " + targetComponent);
                    }
                    mOutputMessageHeader.copyFrom(mInputMessageHeader);
                    mOutputMessageHeader.length = MessageHeader.MESSAGE_HEADER_LENGTH + (result == null ? 1 : result.length());
                    mOutputMessageHeader.flags |= RESPONSE_MASK;
                    mOutputMessageHeader.writeToBuffer(output);
                    if (result == null) {
                        output.put(STRING_TERMINATOR);
                    } else {
                        output.put(result.getBytes(StandardCharsets.US_ASCII));
                    }
                    break;
                case SUBTYPE_HANDSHAKE:
                    Log.v(SERVER_NAME, "Client sent invalid handshake.");
                    return RESPONSE_RECONNECT;
            }
            return RESPONSE_OK;
        }

        @Override
        public int update(long frameStartTime, ByteBuffer output) {
            if (mDoHeartBeat) {
                if (mHeartbeat == mOutstandingHeartbeat) {
                    if (frameStartTime - mLastHeartbeatTime > HEARTBEAT_INTERVAL) {
                        MessageHeader.writeToBuffer(
                            output,
                            MessageHeader.MESSAGE_HEADER_LENGTH,
                            mHeartbeat,
                            (short)0,
                            REQUEST_FLAGS,
                            ProfilerRegistry.SERVER,
                            SUBTYPE_PING);
                        Log.v(SERVER_NAME, "Ping");

                        mLastHeartbeatTime = frameStartTime;
                        mHeartbeat++;
                    }
                } else {
                    if (frameStartTime - mLastHeartbeatTime > HEARTBEAT_TIMEOUT) {
                        Log.i(SERVER_NAME, "Connection timed out");
                        return UPDATE_ERROR_RECONNECT;
                    }
                }
            }

            return UPDATE_DONE;
        }

        private int processHandshake(long frameStartTime, LocalSocket localSocket) throws IOException {
            int totalRead = 0;
            try {
                while (totalRead < HANDSHAKE_HEADER_LENGTH &&
                       SystemClock.elapsedRealtimeNanos() - frameStartTime < HEARTBEAT_TIMEOUT &&
                       !mContinueRunningLatch.await(SERVER_UPDATE_TIME_NS, TimeUnit.NANOSECONDS)) {
                    int bytesRead = localSocket.getInputStream().read(mInputArray, mInputBuffer.position(), mInputBuffer.limit());
                    mInputBuffer.position(mInputBuffer.position() + bytesRead);
                    totalRead += bytesRead;
                }
                if (SystemClock.elapsedRealtimeNanos() - frameStartTime >= HEARTBEAT_TIMEOUT ||
                        mContinueRunningLatch.getCount() == 0) {
                    Log.v(SERVER_NAME, "Connection timed out before handshake completed.");
                    return RESPONSE_RECONNECT;
                }
            } catch (InterruptedException e) {
                Log.v(SERVER_NAME, "Server stopped before handshake completed.");
                return RESPONSE_RECONNECT;
            }

            mInputBuffer.flip();
            mInputMessageHeader.parseFromBuffer(mInputBuffer);

            if (mInputMessageHeader.type != ProfilerRegistry.SERVER ||
                    mInputMessageHeader.subType != SUBTYPE_HANDSHAKE ||
                    mInputBuffer.limit() != HANDSHAKE_HEADER_LENGTH) {
                Log.v(SERVER_NAME, "Client did not send only handshake as first message. Reconnecting.");
                return RESPONSE_RECONNECT;
            }

            // Process handshake and send confirmation.
            mOutputMessageHeader.copyFrom(mInputMessageHeader);
            mOutputMessageHeader.length = MessageHeader.MESSAGE_HEADER_LENGTH + 1; // 1 extra byte for the response.
            mOutputMessageHeader.flags = RESPONSE_MASK;
            mOutputMessageHeader.writeToBuffer(mOutputBuffer);
            if (mInputMessageHeader.length != HANDSHAKE_HEADER_LENGTH || mInputBuffer.getInt() != VERSION) {
                mOutputBuffer.put(ERROR_RESPONSE);
                if (mInputMessageHeader.length != HANDSHAKE_HEADER_LENGTH) {
                    Log.e(SERVER_NAME, "Invalid handshake message.");
                } else {
                    Log.e(SERVER_NAME, "Incompatible client version.");
                }
                return RESPONSE_RECONNECT;
            }
            mOutputBuffer.put(OK_RESPONSE);
            flushBuffer(localSocket);
            mDoHeartBeat = true;
            Log.v(SERVER_NAME, "Handshake Done");
            mInputBuffer.compact();
            return RESPONSE_OK;
        }
    }
}
