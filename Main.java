import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.*;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

public class Main {

    private static final int PORT = 8080;
    private static final String DB_URL = "jdbc:sqlite:cinebook.db";
    private static final Map<String, String> activeSessions = new ConcurrentHashMap<>();

    public static void main(String[] args) throws IOException {
        Database.initialize();

        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);
        server.setExecutor(Executors.newFixedThreadPool(10)); // Thread-pool for scalable concurrent execution

        // Static Asset Route
        server.createContext("/", new Handlers.StaticFileHandler());

        // Authentication Endpoints
        server.createContext("/api/register", new Handlers.RegisterHandler());
        server.createContext("/api/login", new Handlers.LoginHandler());
        server.createContext("/api/logout", new Handlers.LogoutHandler());
        server.createContext("/api/user", new Handlers.UserHandler());

        // Catalog & Multiplex Endpoints
        server.createContext("/api/movies", new Handlers.MoviesHandler());
        server.createContext("/api/shows", new Handlers.ShowsHandler());

        // Booking Operations
        server.createContext("/api/book", new Handlers.BookingHandler());
        server.createContext("/api/bookings", new Handlers.BookingsListHandler());

        System.out.println("=================================================");
        System.out.println(" BookMyMovie Enterprise Engine Online ");
        System.out.println(" Address: http://localhost:" + PORT);
        System.out.println("=================================================");
        server.start();
    }

    // =========================================================================
    // 1. RELATIONAL DATABASE & SCHEMAS
    // =========================================================================
    static class Database {
        public static void initialize() {
            try {
                Class.forName("org.sqlite.JDBC");
            } catch (ClassNotFoundException e) {
                System.err.println("[Database Error] SQLite JDBC Driver not found in classpath: " + e.getMessage());
                return;
            }

            try (Connection conn = DriverManager.getConnection(DB_URL);
                 Statement stmt = conn.createStatement()) {

                // Schema Self-Healing: Verify whether legacy booking table needs migration
                if (isTableOutdated(conn, "bookings", "seats")) {
                    System.out.println("[Migration] Legacy schema detected. Rebuilding table structure...");
                    stmt.execute("DROP TABLE IF EXISTS bookings");
                }

                // 1. Users Schema
                stmt.execute("CREATE TABLE IF NOT EXISTS users (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                        "username TEXT UNIQUE NOT NULL, " +
                        "password TEXT NOT NULL)");

                // 2. Theaters Schema
                stmt.execute("CREATE TABLE IF NOT EXISTS theaters (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                        "name TEXT NOT NULL, " +
                        "location TEXT NOT NULL, " +
                        "screen_type TEXT NOT NULL)");

                // 3. Movies Schema
                stmt.execute("CREATE TABLE IF NOT EXISTS movies (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                        "title TEXT NOT NULL, " +
                        "genre TEXT NOT NULL, " +
                        "rating TEXT NOT NULL, " +
                        "poster TEXT NOT NULL)");

                // 4. Shows Schema
                stmt.execute("CREATE TABLE IF NOT EXISTS shows (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                        "movie_id INTEGER NOT NULL, " +
                        "theater_id INTEGER NOT NULL, " +
                        "show_date TEXT NOT NULL, " +
                        "show_time TEXT NOT NULL, " +
                        "price REAL NOT NULL, " +
                        "FOREIGN KEY(movie_id) REFERENCES movies(id), " +
                        "FOREIGN KEY(theater_id) REFERENCES theaters(id))");

                // 5. Bookings Schema
                stmt.execute("CREATE TABLE IF NOT EXISTS bookings (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                        "user_name TEXT NOT NULL, " +
                        "show_id INTEGER NOT NULL, " +
                        "movie_title TEXT NOT NULL, " +
                        "theater_name TEXT NOT NULL, " +
                        "showtime_info TEXT NOT NULL, " +
                        "seats TEXT NOT NULL, " +
                        "total_amount REAL NOT NULL, " +
                        "FOREIGN KEY(show_id) REFERENCES shows(id))");

                seedInitialData(conn);
                System.out.println("[Database] Relational schemas verified and ready.");
            } catch (SQLException e) {
                System.err.println("[Database Error] Initialization failed: " + e.getMessage());
            }
        }

        private static boolean isTableOutdated(Connection conn, String tableName, String requiredColumn) {
            String sql = "PRAGMA table_info(" + tableName + ")";
            try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(sql)) {
                while (rs.next()) {
                    if (requiredColumn.equalsIgnoreCase(rs.getString("name"))) {
                        return false; // Table contains required column
                    }
                }
            } catch (SQLException ignored) {}
            return true; // Table does not exist or lacks key fields
        }

        private static void seedInitialData(Connection conn) throws SQLException {
            try (Statement checkStmt = conn.createStatement();
                 ResultSet rs = checkStmt.executeQuery("SELECT COUNT(*) FROM movies")) {
                if (rs.next() && rs.getInt(1) > 0) return; // Seed only if catalog is empty
            }

            Statement stmt = conn.createStatement();

            // Movies
            stmt.execute("INSERT INTO movies (title, genre, rating, poster) VALUES " +
                    "('Inception', 'Sci-Fi / Action', '8.8/10', 'https://images.unsplash.com/photo-1536440136628-849c177e76a1?q=80&w=800&auto=format&fit=crop'), " +
                    "('Interstellar', 'Sci-Fi / Drama', '8.7/10', 'https://images.unsplash.com/photo-1451187580459-43490279c0fa?q=80&w=800&auto=format&fit=crop'), " +
                    "('The Dark Knight', 'Action / Crime', '9.0/10', 'https://images.unsplash.com/photo-1509198397868-475647b2a1e5?q=80&w=800&auto=format&fit=crop')");

            // Multiplex Venues
            stmt.execute("INSERT INTO theaters (name, location, screen_type) VALUES " +
                    "('PVR: VR Mall', 'Downtown', '4K Dolby Atmos • Luxe Recliners'), " +
                    "('INOX: Express Avenue', 'Central', 'IMAX 3D • Laser Projection'), " +
                    "('SPI: Luxe Multiplex', 'Midtown', 'LUXE Screen • RGB Laser')");

            // Showtime Schedules
            stmt.execute("INSERT INTO shows (movie_id, theater_id, show_date, show_time, price) VALUES " +
                    "(1, 1, 'Today', '10:00 AM', 150.00), (1, 1, 'Today', '02:30 PM', 180.00), (1, 1, 'Today', '07:00 PM', 200.00), " +
                    "(1, 2, 'Today', '01:15 PM', 220.00), (1, 2, 'Today', '08:30 PM', 250.00), " +
                    "(2, 2, 'Today', '11:00 AM', 200.00), (2, 3, 'Today', '04:15 PM', 180.00), (2, 3, 'Today', '09:00 PM', 210.00), " +
                    "(3, 1, 'Today', '09:30 PM', 190.00), (3, 3, 'Today', '01:00 PM', 170.00)");

            stmt.close();
            System.out.println("[Database] Mock catalog, multiplexes, and showtimes seeded.");
        }
    }

    // =========================================================================
    // 2. SECURITY & SESSION MANAGEMENT
    // =========================================================================
    static class Security {
        public static String getAuthenticatedUser(HttpExchange exchange) {
            String cookieHeader = exchange.getRequestHeaders().getFirst("Cookie");
            if (cookieHeader != null) {
                String[] cookies = cookieHeader.split(";");
                for (String cookie : cookies) {
                    String[] pair = cookie.trim().split("=");
                    if (pair.length == 2 && "session_token".equals(pair[0])) {
                        return activeSessions.get(pair[1]);
                    }
                }
            }
            return null;
        }
    }

    // =========================================================================
    // 3. HTTP ROUTE HANDLERS
    // =========================================================================
    static class Handlers {

        // Static Asset Server (Serves index.html from 'public' directory)
        static class StaticFileHandler implements HttpHandler {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                String path = exchange.getRequestURI().getPath();
                if ("/".equals(path)) path = "/index.html";

                File file = new File("public" + path);
                if (!file.exists() || file.isDirectory()) {
                    Utils.sendJsonResponse(exchange, 404, "{\"error\":\"404 Resource Not Found\"}");
                    return;
                }

                String contentType = "text/html";
                if (path.endsWith(".css")) contentType = "text/css";
                else if (path.endsWith(".js")) contentType = "application/javascript";

                byte[] fileBytes = Files.readAllBytes(file.toPath());
                exchange.getResponseHeaders().set("Content-Type", contentType);
                exchange.sendResponseHeaders(200, fileBytes.length);
                try (OutputStream os = exchange.getResponseBody()) { os.write(fileBytes); }
            }
        }

        // Account Registration
        static class RegisterHandler implements HttpHandler {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) return;
                String body = Utils.readRequestBody(exchange);
                String username = Utils.extractJsonValue(body, "username");
                String password = Utils.extractJsonValue(body, "password");

                if (username.isEmpty() || password.isEmpty()) {
                    Utils.sendJsonResponse(exchange, 400, "{\"status\":\"error\", \"message\":\"Username and password required\"}");
                    return;
                }

                String sql = "INSERT INTO users(username, password) VALUES(?, ?)";
                try (Connection conn = DriverManager.getConnection(DB_URL);
                     PreparedStatement pstmt = conn.prepareStatement(sql)) {
                    pstmt.setString(1, username);
                    pstmt.setString(2, password);
                    pstmt.executeUpdate();
                    Utils.sendJsonResponse(exchange, 200, "{\"status\":\"success\", \"message\":\"Account created successfully\"}");
                } catch (SQLException e) {
                    Utils.sendJsonResponse(exchange, 400, "{\"status\":\"error\", \"message\":\"Username already exists\"}");
                }
            }
        }

        // Authentication & Session Creation
        static class LoginHandler implements HttpHandler {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) return;
                String body = Utils.readRequestBody(exchange);
                String username = Utils.extractJsonValue(body, "username");
                String password = Utils.extractJsonValue(body, "password");

                String sql = "SELECT * FROM users WHERE username = ? AND password = ?";
                try (Connection conn = DriverManager.getConnection(DB_URL);
                     PreparedStatement pstmt = conn.prepareStatement(sql)) {
                    pstmt.setString(1, username);
                    pstmt.setString(2, password);
                    ResultSet rs = pstmt.executeQuery();

                    if (rs.next()) {
                        String token = UUID.randomUUID().toString();
                        activeSessions.put(token, username);

                        // Set HTTP-Only Cookie Header for Session Management
                        exchange.getResponseHeaders().add("Set-Cookie", "session_token=" + token + "; Path=/; HttpOnly; SameSite=Lax");
                        Utils.sendJsonResponse(exchange, 200, "{\"status\":\"success\", \"username\":\"" + Utils.escapeJson(username) + "\"}");
                    } else {
                        Utils.sendJsonResponse(exchange, 401, "{\"status\":\"error\", \"message\":\"Invalid credentials\"}");
                    }
                } catch (SQLException e) {
                    Utils.sendJsonResponse(exchange, 500, "{\"status\":\"error\", \"message\":\"Internal Database Error\"}");
                }
            }
        }

        // Account Logout
        static class LogoutHandler implements HttpHandler {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                String cookieHeader = exchange.getRequestHeaders().getFirst("Cookie");
                if (cookieHeader != null) {
                    for (String cookie : cookieHeader.split(";")) {
                        String[] pair = cookie.trim().split("=");
                        if (pair.length == 2 && "session_token".equals(pair[0])) {
                            activeSessions.remove(pair[1]);
                        }
                    }
                }
                // Clear cookie on client side
                exchange.getResponseHeaders().add("Set-Cookie", "session_token=; Path=/; Max-Age=0; HttpOnly");
                Utils.sendJsonResponse(exchange, 200, "{\"status\":\"logged_out\"}");
            }
        }

        // Active Session Check Endpoint
        static class UserHandler implements HttpHandler {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                String username = Security.getAuthenticatedUser(exchange);
                if (username != null) {
                    Utils.sendJsonResponse(exchange, 200, "{\"loggedIn\": true, \"username\":\"" + Utils.escapeJson(username) + "\"}");
                } else {
                    Utils.sendJsonResponse(exchange, 200, "{\"loggedIn\": false}");
                }
            }
        }

        // Movies Catalog API
        static class MoviesHandler implements HttpHandler {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                    StringBuilder json = new StringBuilder("[");
                    try (Connection conn = DriverManager.getConnection(DB_URL);
                         Statement stmt = conn.createStatement();
                         ResultSet rs = stmt.executeQuery("SELECT * FROM movies")) {
                        boolean first = true;
                        while (rs.next()) {
                            if (!first) json.append(",");
                            json.append("{")
                                .append("\"id\":").append(rs.getInt("id")).append(",")
                                .append("\"title\":\"").append(Utils.escapeJson(rs.getString("title"))).append("\",")
                                .append("\"genre\":\"").append(Utils.escapeJson(rs.getString("genre"))).append("\",")
                                .append("\"rating\":\"").append(Utils.escapeJson(rs.getString("rating"))).append("\",")
                                .append("\"poster\":\"").append(Utils.escapeJson(rs.getString("poster"))).append("\"")
                                .append("}");
                            first = false;
                        }
                    } catch (SQLException e) {
                        System.err.println("[Error] Movies fetch failed: " + e.getMessage());
                    }
                    json.append("]");
                    Utils.sendJsonResponse(exchange, 200, json.toString());
                }
            }
        }

        // Shows & Occupied Seats Schedule API
        static class ShowsHandler implements HttpHandler {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                StringBuilder json = new StringBuilder("[");
                String sql = "SELECT s.id as show_id, s.show_date, s.show_time, s.price, t.name as theater_name, t.location, t.screen_type, m.title as movie_title " +
                             "FROM shows s JOIN theaters t ON s.theater_id = t.id JOIN movies m ON s.movie_id = m.id";

                try (Connection conn = DriverManager.getConnection(DB_URL);
                     Statement stmt = conn.createStatement();
                     ResultSet rs = stmt.executeQuery(sql)) {

                    boolean first = true;
                    while (rs.next()) {
                        int showId = rs.getInt("show_id");
                        String bookedSeatsJson = getBookedSeatsJson(conn, showId);

                        if (!first) json.append(",");
                        json.append("{")
                            .append("\"showId\":").append(showId).append(",")
                            .append("\"movieTitle\":\"").append(Utils.escapeJson(rs.getString("movie_title"))).append("\",")
                            .append("\"theaterName\":\"").append(Utils.escapeJson(rs.getString("theater_name"))).append("\",")
                            .append("\"location\":\"").append(Utils.escapeJson(rs.getString("location"))).append("\",")
                            .append("\"screenType\":\"").append(Utils.escapeJson(rs.getString("screen_type"))).append("\",")
                            .append("\"showDate\":\"").append(Utils.escapeJson(rs.getString("show_date"))).append("\",")
                            .append("\"showTime\":\"").append(Utils.escapeJson(rs.getString("show_time"))).append("\",")
                            .append("\"price\":").append(rs.getDouble("price")).append(",")
                            .append("\"bookedSeats\":").append(bookedSeatsJson)
                            .append("}");
                        first = false;
                    }
                } catch (SQLException e) {
                    System.err.println("[Error] Shows fetch failed: " + e.getMessage());
                }
                json.append("]");
                Utils.sendJsonResponse(exchange, 200, json.toString());
            }

            private String getBookedSeatsJson(Connection conn, int showId) throws SQLException {
                StringBuilder seatsArray = new StringBuilder("[");
                String sql = "SELECT seats FROM bookings WHERE show_id = " + showId;
                try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(sql)) {
                    boolean first = true;
                    while (rs.next()) {
                        String[] seatArr = rs.getString("seats").split(",");
                        for (String seat : seatArr) {
                            if (seat.trim().isEmpty()) continue;
                            if (!first) seatsArray.append(",");
                            seatsArray.append("\"").append(Utils.escapeJson(seat.trim())).append("\"");
                            first = false;
                        }
                    }
                }
                seatsArray.append("]");
                return seatsArray.toString();
            }
        }

        // Reservation Creator Endpoint
        static class BookingHandler implements HttpHandler {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) return;

                String body = Utils.readRequestBody(exchange);
                String showIdStr = Utils.extractJsonValue(body, "showId");
                String userName = Utils.extractJsonValue(body, "userName");
                String seats = Utils.extractJsonValue(body, "seats");
                String movieTitle = Utils.extractJsonValue(body, "movieTitle");
                String theaterName = Utils.extractJsonValue(body, "theaterName");
                String showtimeInfo = Utils.extractJsonValue(body, "showtimeInfo");
                String amountStr = Utils.extractJsonValue(body, "totalAmount");

                if (showIdStr.isEmpty() || userName.isEmpty() || seats.isEmpty()) {
                    Utils.sendJsonResponse(exchange, 400, "{\"status\":\"error\", \"message\":\"Missing reservation attributes\"}");
                    return;
                }

                String sql = "INSERT INTO bookings(user_name, show_id, movie_title, theater_name, showtime_info, seats, total_amount) VALUES(?, ?, ?, ?, ?, ?, ?)";
                try (Connection conn = DriverManager.getConnection(DB_URL);
                     PreparedStatement pstmt = conn.prepareStatement(sql)) {
                    pstmt.setString(1, userName);
                    pstmt.setInt(2, Integer.parseInt(showIdStr));
                    pstmt.setString(3, movieTitle);
                    pstmt.setString(4, theaterName);
                    pstmt.setString(5, showtimeInfo);
                    pstmt.setString(6, seats);
                    pstmt.setDouble(7, Double.parseDouble(amountStr));
                    pstmt.executeUpdate();

                    Utils.sendJsonResponse(exchange, 200, "{\"status\":\"success\"}");
                } catch (SQLException e) {
                    Utils.sendJsonResponse(exchange, 500, "{\"status\":\"error\", \"message\":\"Reservation processing failed\"}");
                }
            }
        }

        // Active Booking Queries and Reset API
        static class BookingsListHandler implements HttpHandler {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                String method = exchange.getRequestMethod();
                if ("GET".equalsIgnoreCase(method)) {
                    StringBuilder json = new StringBuilder("[");
                    String sql = "SELECT id, user_name, movie_title, theater_name, showtime_info, seats, total_amount FROM bookings ORDER BY id DESC";

                    try (Connection conn = DriverManager.getConnection(DB_URL);
                         Statement stmt = conn.createStatement();
                         ResultSet rs = stmt.executeQuery(sql)) {
                        boolean first = true;
                        while (rs.next()) {
                            if (!first) json.append(",");
                            json.append("{")
                                .append("\"id\":").append(rs.getInt("id")).append(",")
                                .append("\"userName\":\"").append(Utils.escapeJson(rs.getString("user_name"))).append("\",")
                                .append("\"movieTitle\":\"").append(Utils.escapeJson(rs.getString("movie_title"))).append("\",")
                                .append("\"theaterName\":\"").append(Utils.escapeJson(rs.getString("theater_name"))).append("\",")
                                .append("\"showtimeInfo\":\"").append(Utils.escapeJson(rs.getString("showtime_info"))).append("\",")
                                .append("\"seats\":\"").append(Utils.escapeJson(rs.getString("seats"))).append("\",")
                                .append("\"totalAmount\":").append(rs.getDouble("total_amount"))
                                .append("}");
                            first = false;
                        }
                    } catch (SQLException e) {
                        System.err.println("[Error] Bookings fetch failed: " + e.getMessage());
                    }
                    json.append("]");
                    Utils.sendJsonResponse(exchange, 200, json.toString());
                } else if ("DELETE".equalsIgnoreCase(method)) {
                    try (Connection conn = DriverManager.getConnection(DB_URL);
                         Statement stmt = conn.createStatement()) {
                        stmt.executeUpdate("DELETE FROM bookings");
                        Utils.sendJsonResponse(exchange, 200, "{\"status\":\"cleared\"}");
                    } catch (SQLException e) {
                        Utils.sendJsonResponse(exchange, 500, "{\"status\":\"error\"}");
                    }
                }
            }
        }
    }

    // =========================================================================
    // 4. HELPER UTILITIES
    // =========================================================================
    static class Utils {
        public static String readRequestBody(HttpExchange exchange) throws IOException {
            StringBuilder bodyBuilder = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) bodyBuilder.append(line);
            }
            return bodyBuilder.toString();
        }

        public static String extractJsonValue(String json, String key) {
            String pattern = "\"" + key + "\":";
            int start = json.indexOf(pattern);
            if (start == -1) return "";
            start += pattern.length();
            while (start < json.length() && Character.isWhitespace(json.charAt(start))) start++;
            boolean isQuoted = start < json.length() && json.charAt(start) == '"';
            if (isQuoted) start++;
            int end = start;
            while (end < json.length()) {
                char c = json.charAt(end);
                if (isQuoted && c == '"') break;
                if (!isQuoted && (c == ',' || c == '}')) break;
                end++;
            }
            return json.substring(start, end).trim();
        }

        public static String escapeJson(String input) {
            if (input == null) return "";
            return input.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
        }

        public static void sendJsonResponse(HttpExchange exchange, int statusCode, String responseJson) throws IOException {
            byte[] bytes = responseJson.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(statusCode, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(bytes); }
        }
    }
}