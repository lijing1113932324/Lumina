

package src;

import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

public class HttpProxyServer {

    public static class HttpProxyHandler implements HttpHandler {
        public void handle(HttpExchange exchange) throws IOException {
            String body = readBody(exchange);
            long startTime = System.currentTimeMillis();
            
            try {
                com.google.gson.JsonObject request = new com.google.gson.JsonParser().parse(body).getAsJsonObject();
                String method = request.has("method") ? request.get("method").getAsString() : "GET";
                String url = request.has("url") ? request.get("url").getAsString() : "";
                String headersJson = request.has("headers") ? request.get("headers").getAsString() : "{}";
                String requestBody = request.has("body") ? request.get("body").getAsString() : "";
                
                if (headersJson == null || headersJson.trim().isEmpty()) {
                    headersJson = "{}";
                }
                
                Map<String, String> headers = new HashMap<>();
                try {
                    com.google.gson.JsonObject headersObj = new com.google.gson.JsonParser().parse(headersJson).getAsJsonObject();
                    for (String key : headersObj.keySet()) {
                        headers.put(key, headersObj.get(key).getAsString());
                    }
                } catch (Exception e) {
                    // ignore header parse error
                }
                
                URL targetUrl = new URL(url);
                HttpURLConnection connection = (HttpURLConnection) targetUrl.openConnection();
                connection.setRequestMethod(method);
                connection.setConnectTimeout(30000);
                connection.setReadTimeout(30000);
                
                for (Map.Entry<String, String> entry : headers.entrySet()) {
                    connection.setRequestProperty(entry.getKey(), entry.getValue());
                }
                
                if (!method.equals("GET") && !method.equals("DELETE")) {
                    connection.setDoOutput(true);
                    // POST请求时先加密请求体
                    String encryptedBody = encrypt(requestBody);
                    try (OutputStream os = connection.getOutputStream()) {
                        byte[] input = encryptedBody.getBytes(StandardCharsets.UTF_8);
                        os.write(input, 0, input.length);
                    }
                }
                
                int statusCode = connection.getResponseCode();
                String responseBody = "";
                
                try (BufferedReader br = new BufferedReader(new InputStreamReader(
                    statusCode >= 200 && statusCode < 300 ? connection.getInputStream() : connection.getErrorStream(), 
                    StandardCharsets.UTF_8))) {
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = br.readLine()) != null) {
                        sb.append(line);
                    }
                    responseBody = sb.toString();
                }
                
                long duration = System.currentTimeMillis() - startTime;
                
                String responseJson = String.format(
                    "{\"success\":true,\"status\":%d,\"time\":%d,\"message\":\"Request completed\",\"response\":%s}",
                    statusCode,
                    duration,
                    new com.google.gson.Gson().toJson(decrypt(responseBody))
                );
                sendJson(exchange, responseJson);
                
            } catch (Exception e) {
                long duration = System.currentTimeMillis() - startTime;
                sendJson(exchange, "{\"success\":false,\"status\":0,\"time\":" + duration + ",\"message\":\"" + escapeJson("Error: " + e.getMessage()) + "\",\"response\":\"\"}");
            }
        }
        
        private String decrypt(String encryptedJson) {
            // TODO: 实现你的解密逻辑
            return encryptedJson;
        }
        
        private String encrypt(String plainJson) {
            // TODO: 实现你的加密逻辑
            // 示例：Base64 编码（请替换为你的实际加密算法）
            if (plainJson == null || plainJson.isEmpty()) {
                return plainJson;
            }
            // 这里是加密逻辑占位符，请根据你的加密算法实现
            // 例如：AES加密、自定义加密等
            // 以下是示例代码（使用Base64作为演示）
            return plainJson;
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
    }
}