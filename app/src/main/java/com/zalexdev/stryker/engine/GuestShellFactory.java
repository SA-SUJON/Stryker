package com.zalexdev.stryker.engine;

import com.jcraft.jsch.ChannelShell;
import com.stryker.terminal.backend.TerminalSession;

import java.io.InputStream;
import java.io.OutputStream;

public final class GuestShellFactory implements TerminalSession.RemoteShellFactory {

    private static final GuestShellFactory INSTANCE = new GuestShellFactory();

    private GuestShellFactory() {}

    public static void install() {
        TerminalSession.setRemoteShellFactory(INSTANCE);
    }

    @Override
    public TerminalSession.RemoteShell open(String target, int columns, int rows) throws Exception {
        final ChannelShell channel = GuestSsh.shell(columns, rows);
        final InputStream in = channel.getInputStream();
        final OutputStream out = channel.getOutputStream();
        channel.connect(20_000);

        return new TerminalSession.RemoteShell() {
            @Override
            public InputStream getInputStream() {
                return in;
            }

            @Override
            public OutputStream getOutputStream() {
                return out;
            }

            @Override
            public void resize(int columns, int rows) {
                try {
                    channel.setPtySize(columns, rows, 0, 0);
                } catch (Exception ignored) {
                }
            }

            @Override
            public void close() {
                try {
                    channel.disconnect();
                } catch (Exception ignored) {
                }
            }
        };
    }
}
