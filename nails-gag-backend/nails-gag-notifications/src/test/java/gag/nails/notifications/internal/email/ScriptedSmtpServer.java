package gag.nails.notifications.internal.email;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Сервер SMTP по сценарию — для ответов, которых GreenMail не даёт: отказ после конца письма и молчание на
 * {@code QUIT}. Принимает соединения по одному: приветствие, {@code EHLO} без расширений (ни STARTTLS, ни входа),
 * {@code MAIL}/{@code RCPT} — 250, {@code DATA} — 354, конец письма — заданный ответ, {@code QUIT} — 221 или молчание.
 */
final class ScriptedSmtpServer implements AutoCloseable {

    private final ServerSocket server;
    private final String endOfDataReply;
    private final boolean answersQuit;

    /**
     * @param endOfDataReply ответ на конец письма, например {@code 250 OK} или {@code 554 5.7.1 Rejected}
     * @param answersQuit отвечать ли на {@code QUIT}; нет — сервер молчит, пока клиент не закроет соединение
     */
    ScriptedSmtpServer(String endOfDataReply, boolean answersQuit) throws IOException {
        this.server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        this.endOfDataReply = endOfDataReply;
        this.answersQuit = answersQuit;
        Thread.ofVirtual().name("scripted-smtp").start(this::serve);
    }

    int port() {
        return server.getLocalPort();
    }

    @Override
    public void close() throws IOException {
        server.close();
    }

    private void serve() {
        while (!server.isClosed()) {
            try (var client = server.accept()) {
                talk(client);
            } catch (IOException e) {
                // Сервер закрыт или клиент оборвал соединение — следующее соединение или конец.
            }
        }
    }

    private void talk(Socket client) throws IOException {
        var in = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.ISO_8859_1));
        var out = client.getOutputStream();
        reply(out, "220 scripted ESMTP");
        String line;
        while ((line = in.readLine()) != null) {
            var command = line.length() < 4 ? line : line.substring(0, 4).toUpperCase(Locale.ROOT);
            switch (command) {
                case "EHLO", "HELO" -> reply(out, "250 scripted");
                case "MAIL", "RCPT", "RSET", "NOOP" -> reply(out, "250 OK");
                case "DATA" -> {
                    reply(out, "354 End data with <CR><LF>.<CR><LF>");
                    String data;
                    while ((data = in.readLine()) != null && !data.equals(".")) {
                        // Тело письма сценарию не нужно.
                    }
                    reply(out, endOfDataReply);
                }
                case "QUIT" -> {
                    if (answersQuit) {
                        reply(out, "221 Bye");
                        return;
                    }
                }
                default -> reply(out, "500 Unknown command");
            }
        }
    }

    private static void reply(OutputStream out, String line) throws IOException {
        out.write((line + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
    }
}
