package de.flothari.regalsystem;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

/*
 * grundsätzliche Funktion
 */

public class ScanCodeAuswertung {

    public static void main(String[] args) throws Exception {
        int port = 8080;

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/", new PostHandler());
        server.setExecutor(null);

        System.out.println("Server läuft auf Port " + port + " …");
        server.start();
    }

    static class PostHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {

            if (!exchange.getRequestMethod().equalsIgnoreCase("POST")) {
                String msg = "Only POST is supported\n";
                exchange.sendResponseHeaders(405, msg.length());
                OutputStream os = exchange.getResponseBody();
                os.write(msg.getBytes());
                os.close();
                return;
            }

            // Body lesen
            InputStream is = exchange.getRequestBody();
            String body = new String(is.readAllBytes(), StandardCharsets.UTF_8);

            System.out.println("POST empfangen:");
            System.out.println(body);

            // --- Scancode extrahieren ---
            // einfache JSON-Auswertung ohne externe Libraries
            int scancode = parseScancodeFromJson(body);

            // --- Scancode auswerten ---
            switch (scancode) {
                case 115:
                    System.out.println("Scancode 115 erkannt!");
                    break;

                case 114:
                    System.out.println("Scancode 114 erkannt!");
                    break;

                default:
                    System.out.println("Unbekannter Scancode: " + scancode);
                    break;
            }

            // Antwort senden
            String response = "{\"status\":\"ok\"}";
            Headers headers = exchange.getResponseHeaders();
            headers.add("Content-Type", "application/json");

            exchange.sendResponseHeaders(200, response.length());
            OutputStream os = exchange.getResponseBody();
            os.write(response.getBytes());
            os.close();
        }
    }

    // --- Eine kleine Hilfsfunktion zum Parsen ohne JSON-Bibliotheken ---
    private static int parseScancodeFromJson(String json) {
        try {
            json = json.replace(" ", "");
            String key = "\"scancode\":";
            int index = json.indexOf(key);
            if (index < 0) return -1;

            int start = index + key.length();
            int end = start;

            // bis Zahl zu Ende ist
            while (end < json.length() && Character.isDigit(json.charAt(end))) {
                end++;
            }

            return Integer.parseInt(json.substring(start, end));
        } catch (Exception e) {
            System.out.println("Konnte Scancode nicht aus JSON lesen: " + e.getMessage());
            return -1;
        }
    }
}