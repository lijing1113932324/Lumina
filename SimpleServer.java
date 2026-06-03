import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;
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
        server.createContext("/api/db/query", new DbQueryHandler());
        server.createContext("/api/config", new ConfigHandler());
        
        server.setExecutor(null);
        server.start();
        
        System.out.println("========================================");
        System.out.println("  Toolkit Service Started");
        System.out.println("  Port: " + port);
        System.out.println("  Access: http://localhost:" + port);
        System.out.println("========================================");
    }
    
    private static void loadPortFromConfig() {
        File configFile = new File("config.json");
        if (!configFile.exists()) {
            configFile = new File(System.getProperty("user.dir") + File.separator + "config.json");
        }
        
        if (configFile.exists()) {
            try {
                String content = new String(Files.readAllBytes(configFile.toPath()), StandardCharsets.UTF_8);
                com.google.gson.JsonObject obj = new com.google.gson.JsonParser().parse(content).getAsJsonObject();
                if (obj.has("server")) {
                    com.google.gson.JsonObject server = obj.getAsJsonObject("server");
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
            
            File file = new File("." + path);
            if (!file.exists()) {
                sendError(exchange, 404, "File not found");
                return;
            }
            
            String contentType = getContentType(path);
            byte[] content = Files.readAllBytes(file.toPath());
            
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
                Object obj = new com.google.gson.JsonParser().parse(body);
                String result = new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(obj);
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
                Object obj = new com.google.gson.JsonParser().parse(body);
                String result = new com.google.gson.Gson().toJson(obj);
                sendJson(exchange, "{\"success\":true,\"result\":\"" + escapeJson(result) + "\",\"message\":\"Minified successfully\"}");
            } catch (Exception e) {
                sendJson(exchange, "{\"success\":false,\"result\":null,\"message\":\"JSON format error: " + e.getMessage() + "\"}");
            }
        }
    }

    static class ConfigHandler implements HttpHandler {
        public void handle(HttpExchange exchange) throws IOException {
            File configFile = new File("config.json");
            if (!configFile.exists()) {
                configFile = new File(System.getProperty("user.dir") + File.separator + "config.json");
            }
            
            if (configFile.exists()) {
                String content = new String(Files.readAllBytes(configFile.toPath()), StandardCharsets.UTF_8);
                try {
                    new com.google.gson.JsonParser().parse(content);
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

    static class DbQueryHandler implements HttpHandler {
        public void handle(HttpExchange exchange) throws IOException {
            String body = readBody(exchange);
            try {
                Map<String, String> params = parseJsonParams(body);
                
                String host = params.get("host");
                String portStr = params.get("port");
                int port = Integer.parseInt(portStr != null ? portStr : "3306");
                String database = params.get("database");
                String username = params.get("username");
                String password = params.get("password");
                String sql = params.get("sql");
                
                if (sql == null || sql.trim().isEmpty()) {
                    sendJson(exchange, "{\"success\":false,\"message\":\"Please enter SQL statement\"}");
                    return;
                }
                
                if (!sql.trim().toUpperCase().startsWith("SELECT")) {
                    sendJson(exchange, "{\"success\":false,\"message\":\"Only SELECT statements are supported\"}");
                    return;
                }
                
                String url = String.format("jdbc:mysql://%s:%d/%s?useSSL=false&serverTimezone=UTC&allowPublicKeyRetrieval=true&characterEncoding=GBK", host, port, database);
                
                try (Connection conn = DriverManager.getConnection(url, username, password);
                     Statement stmt = conn.createStatement();
                     ResultSet rs = stmt.executeQuery(sql)) {
                    
                    ResultSetMetaData metaData = rs.getMetaData();
                    int colCount = metaData.getColumnCount();
                    List<String> columns = new ArrayList<>();
                    for (int i = 1; i <= colCount; i++) {
                        columns.add(metaData.getColumnName(i));
                    }
                    
                    List<Map<String, String>> data = new ArrayList<>();
                    while (rs.next()) {
                        Map<String, String> row = new HashMap<>();
                        for (String col : columns) {
                            row.put(col, rs.getString(col));
                        }
                        data.add(row);
                    }
                    
                    StringBuilder json = new StringBuilder();
                    json.append("{\"success\":true,\"message\":\"Query successful\",\"columns\":[");
                    for (int i = 0; i < columns.size(); i++) {
                        if (i > 0) json.append(",");
                        json.append("\"").append(escapeJson(columns.get(i))).append("\"");
                    }
                    json.append("],\"data\":[");
                    for (int i = 0; i < data.size(); i++) {
                        if (i > 0) json.append(",");
                        json.append("{");
                        Map<String, String> row = data.get(i);
                        int j = 0;
                        for (String col : columns) {
                            if (j > 0) json.append(",");
                            json.append("\"").append(escapeJson(col)).append("\":\"").append(escapeJson(row.get(col))).append("\"");
                            j++;
                        }
                        json.append("}");
                    }
                    json.append("],\"rowCount\":").append(data.size()).append("}");
                    
                    sendJson(exchange, json.toString());
                }
            } catch (SQLException e) {
                sendJson(exchange, "{\"success\":false,\"message\":\"" + escapeJson("Database connection or query failed: " + e.getMessage()) + "\"}");
            } catch (Exception e) {
                sendJson(exchange, "{\"success\":false,\"message\":\"" + escapeJson("Error: " + e.getMessage()) + "\"}");
            }
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
            com.google.gson.JsonObject obj = new com.google.gson.JsonParser().parse(json).getAsJsonObject();
            for (String key : obj.keySet()) {
                params.put(key, obj.get(key).getAsString());
            }
        } catch (Exception e) {
            // ignore
        }
        return params;
    }
}