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

public class ShutterHttpServer
{
	public static void main(String[] args) throws Exception
	{
		int port = 8080;

		// keine explizite ip-adresse erforderlich - es wird auff allen verfügbaren interfaces gesucht
		HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
		server.createContext("/", new PostHandler());
		server.setExecutor(null);

		System.out.println("Server läuft auf Port " + port + " …");
		server.start();
	}

	static class PostHandler implements HttpHandler
	{
		@Override
		public void handle(HttpExchange exchange) throws IOException
		{

			if (!exchange.getRequestMethod().equalsIgnoreCase("POST"))
			{
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
}
