package src;

import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.*;
import java.sql.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class DbQueryServer {

    public static class DbQueryHandler implements HttpHandler {
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
                
                System.out.println("\n========================================");
                System.out.println("Database Query Request");
                System.out.println("========================================");
                System.out.println("Host: " + host);
                System.out.println("Port: " + port);
                System.out.println("Database: " + database);
                System.out.println("Username: " + username);
                System.out.println("SQL: " + sql);
                System.out.println("----------------------------------------");
                
                try (Connection conn = DriverManager.getConnection(url, username, password);
                     Statement stmt = conn.createStatement();
                     ResultSet rs = stmt.executeQuery(sql)) {
                    
                    System.out.println("✓ Database connection successful!");
                    
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
                    
                    System.out.println("✓ Query executed successfully");
                    System.out.println("Columns: " + columns);
                    System.out.println("Rows returned: " + data.size());
                    if (data.size() > 0) {
                        System.out.println("First row sample: " + data.get(0));
                    }
                    System.out.println("========================================\n");
                    
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
                System.out.println("\n========================================");
                System.out.println("Database Error");
                System.out.println("========================================");
                System.out.println("Error: " + e.getMessage());
                System.out.println("SQL State: " + e.getSQLState());
                System.out.println("Error Code: " + e.getErrorCode());
                System.out.println("========================================\n");
                sendJson(exchange, "{\"success\":false,\"message\":\"" + escapeJson("Database connection or query failed: " + e.getMessage()) + "\"}");
            } catch (Exception e) {
                System.out.println("\n========================================");
                System.out.println("Unexpected Error");
                System.out.println("========================================");
                System.out.println("Error: " + e.getMessage());
                e.printStackTrace();
                System.out.println("========================================\n");
                sendJson(exchange, "{\"success\":false,\"message\":\"" + escapeJson("Error: " + e.getMessage()) + "\"}");
            }
        }
        
        private String readBody(HttpExchange exchange) throws IOException {
            InputStream is = exchange.getRequestBody();
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            int len;
            while ((len = is.read(buffer)) != -1) {
                baos.write(buffer, 0, len);
            }
            return new String(baos.toByteArray(), StandardCharsets.UTF_8);
        }
        
        private Map<String, String> parseJsonParams(String json) {
            Map<String, String> params = new HashMap<>();
            try {
                JsonObject obj = new JsonParser().parse(json).getAsJsonObject();
                for (String key : obj.keySet()) {
                    params.put(key, obj.get(key).getAsString());
                }
            } catch (Exception e) {
                // ignore parse error
            }
            return params;
        }
        
        private void sendJson(HttpExchange exchange, String json) throws IOException {
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        }
        
        private String escapeJson(String str) {
            if (str == null) {
                return "";
            }
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < str.length(); i++) {
                char c = str.charAt(i);
                switch (c) {
                    case '"': sb.append("\\\""); break;
                    case '\\': sb.append("\\\\"); break;
                    case '/': sb.append("\\/"); break;
                    case '\b': sb.append("\\b"); break;
                    case '\f': sb.append("\\f"); break;
                    case '\n': sb.append("\\n"); break;
                    case '\r': sb.append("\\r"); break;
                    case '\t': sb.append("\\t"); break;
                    default:
                        if (c < 32) {
                            sb.append("\\u").append(String.format("%04x", (int) c));
                        } else {
                            sb.append(c);
                        }
                }
            }
            return sb.toString();
        }
    }
}
