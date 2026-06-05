package src;

import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;
import java.io.*;
import java.nio.charset.StandardCharsets;

public class CryptoServer {

    public static class EncryptHandler implements HttpHandler {
        public void handle(HttpExchange exchange) throws IOException {
            String body = readBody(exchange);
            String text = extractParam(body, "text");
            
            String result = encrypt(text);
            
            String response = "{\"success\":true,\"result\":\"" + escapeJson(result) + "\"}";
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
            byte[] responseBytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, responseBytes.length);
            exchange.getResponseBody().write(responseBytes);
            exchange.close();
        }
    }

    public static class DecryptHandler implements HttpHandler {
        public void handle(HttpExchange exchange) throws IOException {
            String body = readBody(exchange);
            String text = extractParam(body, "text");
            
            String result = decrypt(text);
            
            String response = "{\"success\":true,\"result\":\"" + escapeJson(result) + "\"}";
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
            byte[] responseBytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, responseBytes.length);
            exchange.getResponseBody().write(responseBytes);
            exchange.close();
        }
    }
    
    public static String encrypt(String plainText) {
        // TODO: 实现你的加密逻辑
        // 输入：明文字符串
        // 输出：加密后的字符串
        return plainText;
    }
    
    public static String decrypt(String encryptedText) {
        // TODO: 实现你的解密逻辑
        // 输入：加密字符串
        // 输出：解密后的字符串
        return encryptedText;
    }
    
    private static String extractParam(String body, String paramName) {
        try {
            com.google.gson.JsonObject obj = new com.google.gson.JsonParser().parse(body).getAsJsonObject();
            if (obj.has(paramName)) {
                return obj.get(paramName).getAsString();
            }
        } catch (Exception e) {
            // ignore
        }
        return "";
    }
    
    private static String escapeJson(String str) {
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
    
    private static String readBody(HttpExchange exchange) throws IOException {
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