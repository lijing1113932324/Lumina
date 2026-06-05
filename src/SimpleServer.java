

package src;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.*;
import java.util.*;

public class SimpleServer {
    private static int port = 8080;

    public static void main(String[] args) throws Exception {
        loadPortFromConfig();
        
        int availablePort = findAvailablePort(port);
        if (availablePort != port) {
            System.out.println("Warning: Port " + port + " is already in use, using port " + availablePort);
            port = availablePort;
        }
        
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        
        server.createContext("/", new StaticFileHandler());
        server.createContext("/api/timestamp", new TimestampHandler());
        server.createContext("/api/json/format", new JsonFormatHandler());
        server.createContext("/api/json/minify", new JsonMinifyHandler());
        server.createContext("/api/db/query", new DbQueryServer.DbQueryHandler());
        server.createContext("/api/config", new ConfigHandler());
        server.createContext("/api/http/proxy", new HttpProxyServer.HttpProxyHandler());
        server.createContext("/api/encrypt", new CryptoServer.EncryptHandler());
        server.createContext("/api/decrypt", new CryptoServer.DecryptHandler());
        
        server.setExecutor(null);
        server.start();
        
        System.out.println("========================================");
        System.out.println("  Toolkit Service Started");
        System.out.println("  Port: " + port);
        System.out.println("  Access: http://localhost:" + port);
        System.out.println("========================================");
        
        // 保持服务运行
        Thread.currentThread().join();
    }
    
    private static File findConfigFile() {
        // 尝试多个位置查找配置文件
        File[] possiblePaths = {
            new File("config.json"),
            new File(System.getProperty("user.dir") + File.separator + "config.json"),
            new File("src/config.json"),
            new File(System.getProperty("user.dir") + File.separator + "src" + File.separator + "config.json")
        };
        
        for (File file : possiblePaths) {
            if (file.exists()) {
                return file;
            }
        }
        return null;
    }
    
    private static void loadPortFromConfig() {
        File configFile = findConfigFile();
        
        if (configFile != null) {
            try {
                String content = readFileContent(configFile);
                JsonObject obj = new JsonParser().parse(content).getAsJsonObject();
                if (obj.has("server")) {
                    JsonObject server = obj.getAsJsonObject("server");
                    if (server.has("port")) {
                        port = server.get("port").getAsInt();
                    }
                }
            } catch (Exception e) {
                System.out.println("Failed to read config file, using default port: " + e.getMessage());
            }
        }
    }
    
    private static int findAvailablePort(int startPort) {
        for (int p = startPort; p < startPort + 100; p++) {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("localhost", p), 100);
            } catch (IOException e) {
                return p;
            }
        }
        return startPort;
    }

    static class StaticFileHandler implements HttpHandler {
        public void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/")) path = "/index.html";
            
            File file = new File("html" + path);
            if (!file.exists()) {
                sendError(exchange, 404, "File not found");
                return;
            }
            
            String contentType = getContentType(path);
            byte[] content = readFileBytes(file);
            
            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.sendResponseHeaders(200, content.length);
            exchange.getResponseBody().write(content);
            exchange.close();
        }
        
        private String getContentType(String path) {
            if (path.endsWith(".html")) return "text/html; charset=UTF-8";
            if (path.endsWith(".js")) return "text/javascript; charset=UTF-8";
            if (path.endsWith(".css")) return "text/css; charset=UTF-8";
            return "application/octet-stream";
        }
    }

    static class TimestampHandler implements HttpHandler {
        public void handle(HttpExchange exchange) throws IOException {
            long now = System.currentTimeMillis();
            String json = String.format(
                "{\"milliseconds\":%d,\"seconds\":%d,\"dateTime\":\"%s\"}",
                now,
                now / 1000,
                new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS").format(new java.util.Date())
            );
            sendJson(exchange, json);
        }
    }

    static class JsonFormatHandler implements HttpHandler {
        public void handle(HttpExchange exchange) throws IOException {
            String body = readBody(exchange);
            try {
                Object obj = new JsonParser().parse(body);
            String result = new GsonBuilder().setPrettyPrinting().create().toJson(obj);
            sendJson(exchange, "{\"success\":true,\"result\":\"" + escapeJson(result) + "\",\"message\":\"Formatted successfully\"}");
            } catch (Exception e) {
                sendJson(exchange, "{\"success\":false,\"result\":null,\"message\":\"JSON format error: " + e.getMessage() + "\"}");
            }
        }
    }

    static class JsonMinifyHandler implements HttpHandler {
        public void handle(HttpExchange exchange) throws IOException {
            String body = readBody(exchange);
            try {
                Object obj = new JsonParser().parse(body);
            String result = new Gson().toJson(obj);
            sendJson(exchange, "{\"success\":true,\"result\":\"" + escapeJson(result) + "\",\"message\":\"Minified successfully\"}");
            } catch (Exception e) {
                sendJson(exchange, "{\"success\":false,\"result\":null,\"message\":\"JSON format error: " + e.getMessage() + "\"}");
            }
        }
    }

    static class ConfigHandler implements HttpHandler {
        public void handle(HttpExchange exchange) throws IOException {
            File configFile = findConfigFile();
            
            if (configFile != null) {
                String content = readFileContent(configFile);
                try {
                    new JsonParser().parse(content);
                    exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
                    exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
                    exchange.getResponseHeaders().set("Cache-Control", "no-cache, no-store, must-revalidate");
                    exchange.getResponseHeaders().set("Pragma", "no-cache");
                    exchange.getResponseHeaders().set("Expires", "0");
                    byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, bytes.length);
                    exchange.getResponseBody().write(bytes);
                } catch (Exception e) {
                    String defaultConfig = "{\"database\":{\"host\":\"localhost\",\"port\":3306,\"database\":\"\",\"username\":\"\",\"password\":\"\"}}";
                    sendJson(exchange, defaultConfig);
                }
            } else {
                String defaultConfig = "{\"database\":{\"host\":\"localhost\",\"port\":3306,\"database\":\"\",\"username\":\"\",\"password\":\"\"},\"server\":{\"port\":8080},\"query\":{\"exampleSql\":\"SELECT * FROM your_table LIMIT 10\"}}";
                sendJson(exchange, defaultConfig);
            }
            exchange.close();
        }
    }

    private static String readBody(HttpExchange exchange) throws IOException {
        InputStream is = exchange.getRequestBody();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int len;
        while ((len = is.read(buffer)) != -1) {
            baos.write(buffer, 0, len);
        }
        return baos.toString("UTF-8");
    }

    private static void sendJson(HttpExchange exchange, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static void sendError(HttpExchange exchange, int code, String message) throws IOException {
        byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(code, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static String escapeJson(String str) {
        if (str == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < str.length(); i++) {
            char c = str.charAt(i);
            if (c == '\\') {
                sb.append("\\\\");
            } else if (c == '\"') {
                sb.append("\\\"");
            } else if (c == '\n') {
                sb.append("\\n");
            } else if (c == '\r') {
                sb.append("\\r");
            } else if (c == '\t') {
                sb.append("\\t");
            } else if (c == '\b') {
                sb.append("\\b");
            } else if (c == '\f') {
                sb.append("\\f");
            } else if (c < 32) {
                sb.append("\\u").append(String.format("%04x", (int) c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static Map<String, String> parseJsonParams(String json) {
        Map<String, String> params = new HashMap<>();
        try {
            JsonObject obj = new JsonParser().parse(json).getAsJsonObject();
            for (String key : obj.keySet()) {
                params.put(key, obj.get(key).getAsString());
            }
        } catch (Exception e) {
            // ignore
        }
        return params;
    }
    
    private static String readFileContent(File file) throws IOException {
        InputStream is = new FileInputStream(file);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int len;
        while ((len = is.read(buffer)) != -1) {
            baos.write(buffer, 0, len);
        }
        is.close();
        return baos.toString("UTF-8");
    }
    
    private static byte[] readFileBytes(File file) throws IOException {
        InputStream is = new FileInputStream(file);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int len;
        while ((len = is.read(buffer)) != -1) {
            baos.write(buffer, 0, len);
        }
        is.close();
        return baos.toByteArray();
    }
}
