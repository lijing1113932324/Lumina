package src;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.jcraft.jsch.*;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * Linux 日志下载工具
 * —— 通过 SFTP 连接 Linux 服务器，下载日志文件到本地 output 目录
 *
 * 依赖：lib/jsch-0.1.55.jar（需自行下载放到 lib 目录）
 * 配置：src/log-downloader-config.json（独立配置文件）
 *
 * 使用方式：
 *   1. 命令行模式：java -cp ... src.LogDownloader
 *   2. HTTP 接口模式：通过 SimpleServer 注册 /api/log/download 路由
 */
public class LogDownloader {

    // ==================== 配置字段 ====================
    private String host;
    private int port = 22;
    private String username;
    private String password;
    private String remoteLogDir;
    private String localOutputDir = "output";

    // ==================== 主入口（命令行模式） ====================
    public static void main(String[] args) throws IOException {
        LogDownloader downloader = new LogDownloader();
        if (!downloader.loadConfig()) {
            System.out.println("[ERROR] 配置文件加载失败，请检查 server.json");
            return;
        }

        System.out.println("========================================");
        System.out.println("  Linux 日志下载工具");
        System.out.println("  远程主机: " + downloader.host + ":" + downloader.port);
        System.out.println("  远程目录: " + downloader.remoteLogDir);
        System.out.println("  本地目录: " + downloader.localOutputDir);
        System.out.println("========================================");

        // 解析命令行参数，支持指定文件名过滤
        String fileFilter = args.length > 0 ? args[0] : null;
        downloader.downloadLogs(fileFilter);
    }

    // ==================== HTTP Handler ====================
    public static class LogDownloadHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            LogDownloader downloader = new LogDownloader();
            if (!downloader.loadConfig()) {
                sendJson(exchange, "{\"success\":false,\"message\":\"SSH 配置未找到，请检查 server.json\"}");
                return;
            }

            // 读取请求参数
            String body = readBody(exchange);
            String fileFilter = null;
            try {
                JsonObject req = new JsonParser().parse(body).getAsJsonObject();
                if (req.has("filter")) {
                    fileFilter = req.get("filter").getAsString();
                }
                if (req.has("remoteDir")) {
                    downloader.remoteLogDir = req.get("remoteDir").getAsString();
                }
            } catch (Exception ignored) {
            }

            // 执行下载
            DownloadResult result = downloader.downloadLogsWithResult(fileFilter);
            String json = String.format(
                    "{\"success\":%b,\"message\":\"%s\",\"count\":%d,\"files\":%s}",
                    result.success,
                    escapeJson(result.message),
                    result.downloadedCount,
                    new com.google.gson.Gson().toJson(result.downloadedFiles)
            );
            sendJson(exchange, json);
        }
    }

    // ==================== 下载结果类 ====================
    public static class DownloadResult {
        boolean success;
        String message;
        int downloadedCount;
        List<String> downloadedFiles = new ArrayList<>();
    }

    // ==================== 核心下载逻辑 ====================
    public void downloadLogs(String fileFilter) {
        DownloadResult result = downloadLogsWithResult(fileFilter);
        System.out.println("\n" + result.message);
        if (!result.downloadedFiles.isEmpty()) {
            System.out.println("已下载文件:");
            for (String f : result.downloadedFiles) {
                System.out.println("  - output/" + f);
            }
        }
    }

    public DownloadResult downloadLogsWithResult(String fileFilter) {
        DownloadResult result = new DownloadResult();
        Session session = null;
        ChannelSftp sftpChannel = null;

        try {
            // 1. 创建 JSch 会话
            JSch jsch = new JSch();
            session = jsch.getSession(username, host, port);
            session.setPassword(password);

            // 跳过主机密钥检查（生产环境建议配置 known_hosts）
            Properties config = new Properties();
            config.put("StrictHostKeyChecking", "no");
            session.setConfig(config);
            session.setTimeout(30000);
            session.connect(15000);

            System.out.println("[INFO] SSH 连接成功: " + host);

            // 2. 打开 SFTP 通道
            Channel channel = session.openChannel("sftp");
            channel.connect(10000);
            sftpChannel = (ChannelSftp) channel;

            System.out.println("[INFO] SFTP 通道已建立");

            // 生成当前日期字符串（格式：yyyyMMdd）
            String dateStr = new SimpleDateFormat("yyyyMMdd").format(new Date());

            // 拼接远程日期路径
            String remoteDatePath = remoteLogDir.endsWith("/") ? remoteLogDir + dateStr : remoteLogDir + "/" + dateStr;
            System.out.println("[INFO] 远程日期路径: " + remoteDatePath);

            // 3. 确保本地日期目录存在并清空已有文件
            Path localDatePath = Paths.get(localOutputDir, dateStr);
            System.out.println("[DEBUG] 本地日期目录路径: " + localDatePath.toAbsolutePath());
            
            if (!Files.exists(localDatePath)) {
                Files.createDirectories(localDatePath);
                System.out.println("[INFO] 创建本地日期目录: " + localDatePath.toAbsolutePath());
            } else {
                // 清空本地日期目录中的已有文件
                System.out.println("[INFO] 清空本地日期目录中的已有文件: " + localDatePath.toAbsolutePath());
                int deletedCount = 0;
                try {
                    java.util.List<Path> filesToDelete = new java.util.ArrayList<>();
                    try (java.util.stream.Stream<Path> stream = Files.list(localDatePath)) {
                        stream.forEach(path -> {
                            if (Files.isRegularFile(path)) {
                                filesToDelete.add(path);
                            }
                        });
                    }
                    
                    System.out.println("[DEBUG] 找到 " + filesToDelete.size() + " 个文件需要删除");
                    
                    for (Path path : filesToDelete) {
                        try {
                            Files.delete(path);
                            System.out.println("[清理] 已删除: " + path.getFileName());
                            deletedCount++;
                        } catch (IOException e) {
                            System.err.println("[WARN] 无法删除文件: " + path.getFileName() + " - " + e.getMessage());
                        }
                    }
                } catch (IOException e) {
                    System.err.println("[ERROR] 列出目录失败: " + e.getMessage());
                }
                System.out.println("[INFO] 本地日期目录已清空，共删除 " + deletedCount + " 个文件");
            }

            // 4. 列出远程日志文件
            Vector<ChannelSftp.LsEntry> entries;
            try {
                entries = sftpChannel.ls(remoteDatePath);
            } catch (SftpException e) {
                result.success = false;
                result.message = "远程日期目录不存在或无法访问: " + remoteDatePath + " (" + e.getMessage() + ")";
                return result;
            }

            // 5. 过滤并下载文件
            int total = 0;
            int downloaded = 0;

            for (ChannelSftp.LsEntry entry : entries) {
                String filename = entry.getFilename();
                if (filename.equals(".") || filename.equals("..")) continue;

                // 跳过目录
                if (entry.getAttrs().isDir()) continue;

                // 文件名过滤
                if (fileFilter != null && !fileFilter.trim().isEmpty()) {
                    if (!matchesFilter(filename, fileFilter.trim())) continue;
                }

                total++;
                String remoteFile = remoteDatePath + "/" + filename;
                String localFile = localDatePath.toString() + File.separator + filename;

                try {
                    System.out.print("[下载] " + filename + " ... ");
                    sftpChannel.get(remoteFile, localFile);
                    long size = new File(localFile).length();
                    System.out.println(formatSize(size) + " 完成");
                    result.downloadedFiles.add(dateStr + File.separator + filename);
                    downloaded++;
                } catch (SftpException e) {
                    System.out.println("失败: " + e.getMessage());
                }
            }

            if (total == 0) {
                result.success = true;
                result.message = "远程日期目录中没有找到匹配的日志文件: " + remoteDatePath;
            } else {
                result.success = true;
                result.message = "共扫描 " + total + " 个文件，成功下载 " + downloaded + " 个到 " +
                        localDatePath.toAbsolutePath();
            }
            result.downloadedCount = downloaded;

        } catch (JSchException e) {
            result.success = false;
            result.message = "SSH 连接失败: " + e.getMessage();
            System.err.println("[ERROR] " + result.message);
        } catch (Exception e) {
            result.success = false;
            result.message = "下载过程出错: " + e.getMessage();
            System.err.println("[ERROR] " + result.message);
            e.printStackTrace();
        } finally {
            if (sftpChannel != null && sftpChannel.isConnected()) {
                sftpChannel.disconnect();
            }
            if (session != null && session.isConnected()) {
                session.disconnect();
                System.out.println("[INFO] 连接已关闭");
            }
        }

        return result;
    }

    // ==================== 配置加载 ====================
    private boolean loadConfig() {
        File configFile = findConfigFile();
        if (configFile == null) {
            System.err.println("[ERROR] 找不到 server.json");
            return false;
        }

        try {
            String content = new String(Files.readAllBytes(configFile.toPath()), StandardCharsets.UTF_8);
            JsonObject cfg = new JsonParser().parse(content).getAsJsonObject();

            this.host = getString(cfg, "host", "localhost");
            this.port = getInt(cfg, "port", 22);
            this.username = getString(cfg, "username", "root");
            this.password = getString(cfg, "password", "");
            this.remoteLogDir = getString(cfg, "remoteLogDir", "/var/log");
            this.localOutputDir = getString(cfg, "localOutputDir", "output");

            return true;
        } catch (Exception e) {
            System.err.println("[ERROR] 解析配置文件失败: " + e.getMessage());
            return false;
        }
    }

    // ==================== 工具方法 ====================
    private File findConfigFile() {
        File[] possiblePaths = {
                new File("server.json"),
                new File(System.getProperty("user.dir") + File.separator + "server.json"),
                new File("src/server.json"),
                new File(System.getProperty("user.dir") + File.separator + "src" + File.separator + "server.json")
        };
        for (File f : possiblePaths) {
            if (f.exists()) return f;
        }
        return null;
    }

    private boolean matchesFilter(String filename, String filter) {
        // 支持简单通配符: * 匹配任意字符, ? 匹配单个字符
        String regex = filter
                .replace(".", "\\.")
                .replace("*", ".*")
                .replace("?", ".");
        return filename.matches("(?i).*" + regex + ".*");
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }

    private static String getString(JsonObject obj, String key, String defaultVal) {
        return obj.has(key) ? obj.get(key).getAsString() : defaultVal;
    }

    private static int getInt(JsonObject obj, String key, int defaultVal) {
        return obj.has(key) ? obj.get(key).getAsInt() : defaultVal;
    }

    // ==================== HTTP 辅助方法 ====================
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

    private static String escapeJson(String str) {
        if (str == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < str.length(); i++) {
            char c = str.charAt(i);
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n");  break;
                case '\r': sb.append("\\r");  break;
                case '\t': sb.append("\\t");  break;
                default:   sb.append(c);
            }
        }
        return sb.toString();
    }
}
