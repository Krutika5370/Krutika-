
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

// --- MAIN SERVER APPLICATION (Must be first class for direct execution) ---
public class HotelReservationApp {

    private static final HotelManager manager = new HotelManager();

    public static void main(String[] args) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);

        server.createContext("/", new StaticFileHandler());
        server.createContext("/api/rooms", new RoomsHandler());
        server.createContext("/api/bookings", new BookingsHandler());

        server.setExecutor(null);
        System.out.println("Hotel Server started successfully on http://localhost:8080");
        server.start();
    }

    static class StaticFileHandler implements HttpHandler {

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            File file = new File("index.html");
            if (!file.exists()) {
                String response = "404 (index.html not found in directory)";
                exchange.sendResponseHeaders(404, response.length());
                OutputStream os = exchange.getResponseBody();
                os.write(response.getBytes());
                os.close();
                return;
            }
            exchange.sendResponseHeaders(200, file.length());
            OutputStream os = exchange.getResponseBody();
            Files.copy(file.toPath(), os);
            os.close();
        }
    }

    static class RoomsHandler implements HttpHandler {

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            setCorsHeaders(exchange);
            String query = exchange.getRequestURI().getQuery();
            String category = "All";
            if (query != null && query.startsWith("category=")) {
                category = query.split("=")[1];
            }

            List<Room> rooms = manager.getRooms(category);
            StringBuilder json = new StringBuilder("[");
            for (int i = 0; i < rooms.size(); i++) {
                Room r = rooms.get(i);
                json.append(String.format("{\"id\":%d,\"name\":\"%s\",\"category\":\"%s\",\"price\":%.1f,\"available\":%b}",
                        r.id, r.name, r.category, r.price, r.available));
                if (i < rooms.size() - 1) {
                    json.append(",");
                }
            }
            json.append("]");

            byte[] responseBytes = json.toString().getBytes("UTF-8");
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, responseBytes.length);
            OutputStream os = exchange.getResponseBody();
            os.write(responseBytes);
            os.close();
        }
    }

    static class BookingsHandler implements HttpHandler {

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            setCorsHeaders(exchange);
            String method = exchange.getRequestMethod();

            if (method.equalsIgnoreCase("GET")) {
                List<Booking> bookings = manager.getBookings();
                StringBuilder json = new StringBuilder("[");
                for (int i = 0; i < bookings.size(); i++) {
                    Booking b = bookings.get(i);
                    json.append(String.format("{\"id\":\"%s\",\"roomId\":%d,\"roomName\":\"%s\",\"category\":\"%s\",\"guestName\":\"%s\",\"email\":\"%s\",\"checkIn\":\"%s\",\"checkOut\":\"%s\",\"totalPrice\":%.1f,\"paymentStatus\":\"%s\"}",
                            b.id, b.roomId, b.roomName, b.category, b.guestName, b.email, b.checkIn, b.checkOut, b.totalPrice, b.paymentStatus));
                    if (i < bookings.size() - 1) {
                        json.append(",");
                    }
                }
                json.append("]");
                byte[] bytes = json.toString().getBytes("UTF-8");
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                OutputStream os = exchange.getResponseBody();
                os.write(bytes);
                os.close();
            } else if (method.equalsIgnoreCase("POST")) {
                BufferedReader reader = new BufferedReader(new InputStreamReader(exchange.getRequestBody(), "UTF-8"));
                StringBuilder body = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    body.append(line);
                }

                String jsonStr = body.toString();
                int roomId = extractInt(jsonStr, "roomId");
                String guestName = extractString(jsonStr, "guestName");
                String email = extractString(jsonStr, "email");
                String checkIn = extractString(jsonStr, "checkIn");
                String checkOut = extractString(jsonStr, "checkOut");

                String result = manager.createBooking(roomId, guestName, email, checkIn, checkOut);
                String response = "{\"status\":\"" + result + "\"}";
                byte[] bytes = response.getBytes("UTF-8");
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(result.equals("Success") ? 201 : 400, bytes.length);
                OutputStream os = exchange.getResponseBody();
                os.write(bytes);
                os.close();
            } else if (method.equalsIgnoreCase("DELETE")) {
                String path = exchange.getRequestURI().getPath();
                String[] parts = path.split("/");
                String bookingId = parts[parts.length - 1];

                boolean success = manager.cancelBooking(bookingId);
                String response = "{\"success\":" + success + "}";
                byte[] bytes = response.getBytes("UTF-8");
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(success ? 200 : 400, bytes.length);
                OutputStream os = exchange.getResponseBody();
                os.write(bytes);
                os.close();
            }
        }
    }

    private static void setCorsHeaders(HttpExchange exchange) {
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, DELETE, OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
    }

    private static int extractInt(String json, String key) {
        try {
            int idx = json.indexOf("\"" + key + "\":");
            if (idx == -1) {
                return 0;
            }
            int start = idx + key.length() + 3;
            int end = start;
            while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '.')) {
                end++;
            }
            return Integer.parseInt(json.substring(start, end).trim());
        } catch (Exception e) {
            return 0;
        }
    }

    private static String extractString(String json, String key) {
        try {
            int idx = json.indexOf("\"" + key + "\":\"");
            if (idx == -1) {
                return "";
            }
            int start = idx + key.length() + 4;
            int end = json.indexOf("\"", start);
            return json.substring(start, end);
        } catch (Exception e) {
            return "";
        }
    }
}

// --- HOTEL MANAGER (File I/O & Business Logic) ---
class HotelManager {

    private final String dataFilePath = "hotel_data.json";
    private List<Room> rooms;
    private List<Booking> bookings;

    public HotelManager() {
        loadData();
    }

    public synchronized void loadData() {
        File file = new File(dataFilePath);
        if (!file.exists()) {
            rooms = new ArrayList<>();
            rooms.add(new Room(101, "Standard Cozy", "Standard", 100.0, true));
            rooms.add(new Room(102, "Standard Twin", "Standard", 120.0, true));
            rooms.add(new Room(201, "Deluxe Ocean View", "Deluxe", 220.0, true));
            rooms.add(new Room(202, "Deluxe City View", "Deluxe", 200.0, true));
            rooms.add(new Room(301, "Presidential Suite", "Suite", 450.0, true));
            rooms.add(new Room(302, "Royal Penthouse Suite", "Suite", 600.0, true));
            bookings = new ArrayList<>();
            saveData();
        } else {
            try {
                String content = new String(Files.readAllBytes(Paths.get(dataFilePath)));
                parseJsonSimple(content);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    public synchronized void saveData() {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n  \"rooms\": [\n");
        for (int i = 0; i < rooms.size(); i++) {
            Room r = rooms.get(i);
            sb.append(String.format("    {\"id\":%d, \"name\":\"%s\", \"category\":\"%s\", \"price\":%.1f, \"available\":%b}%s\n",
                    r.id, r.name, r.category, r.price, r.available, (i < rooms.size() - 1) ? "," : ""));
        }
        sb.append("  ],\n  \"bookings\": [\n");
        for (int i = 0; i < bookings.size(); i++) {
            Booking b = bookings.get(i);
            sb.append(String.format("    {\"id\":\"%s\", \"roomId\":%d, \"roomName\":\"%s\", \"category\":\"%s\", \"guestName\":\"%s\", \"email\":\"%s\", \"checkIn\":\"%s\", \"checkOut\":\"%s\", \"totalPrice\":%.1f, \"paymentStatus\":\"%s\"}%s\n",
                    b.id, b.roomId, b.roomName, b.category, b.guestName, b.email, b.checkIn, b.checkOut, b.totalPrice, b.paymentStatus, (i < bookings.size() - 1) ? "," : ""));
        }
        sb.append("  ]\n}");
        try (FileWriter file = new FileWriter(dataFilePath)) {
            file.write(sb.toString());
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void parseJsonSimple(String content) {
        rooms = Arrays.asList(
                new Room(101, "Standard Cozy", "Standard", 100.0, true),
                new Room(102, "Standard Twin", "Standard", 120.0, true),
                new Room(201, "Deluxe Ocean View", "Deluxe", 220.0, true),
                new Room(202, "Deluxe City View", "Deluxe", 200.0, true),
                new Room(301, "Presidential Suite", "Suite", 450.0, true),
                new Room(302, "Royal Penthouse Suite", "Suite", 600.0, true)
        );
        bookings = new ArrayList<>();
    }

    public List<Room> getRooms(String category) {
        if (category == null || category.equalsIgnoreCase("All")) {
            return rooms;
        }
        List<Room> filtered = new ArrayList<>();
        for (Room r : rooms) {
            if (r.category.equalsIgnoreCase(category)) {
                filtered.add(r);
            }
        }
        return filtered;
    }

    public List<Booking> getBookings() {
        return bookings;
    }

    public synchronized String createBooking(int roomId, String guestName, String email, String checkIn, String checkOut) {
        Room targetRoom = null;
        for (Room r : rooms) {
            if (r.id == roomId) {
                targetRoom = r;
                break;
            }
        }

        if (targetRoom == null || !targetRoom.available) {
            return "Error: Room not available.";
        }

        targetRoom.available = false;
        String bookingId = "BK-" + System.currentTimeMillis();
        Booking newBooking = new Booking(bookingId, roomId, targetRoom.name, targetRoom.category, guestName, email, checkIn, checkOut, targetRoom.price, "Paid (Simulated)");
        bookings.add(newBooking);
        saveData();
        return "Success";
    }

    public synchronized boolean cancelBooking(String bookingId) {
        Booking targetBooking = null;
        for (Booking b : bookings) {
            if (b.id.equals(bookingId)) {
                targetBooking = b;
                break;
            }
        }

        if (targetBooking == null) {
            return false;
        }

        for (Room r : rooms) {
            if (r.id == targetBooking.roomId) {
                r.available = true;
                break;
            }
        }

        bookings.remove(targetBooking);
        saveData();
        return true;
    }
}

// --- OOP MODELS ---
class Room {

    int id;
    String name;
    String category;
    double price;
    boolean available;

    public Room(int id, String name, String category, double price, boolean available) {
        this.id = id;
        this.name = name;
        this.category = category;
        this.price = price;
        this.available = available;
    }
}

class Booking {

    String id;
    int roomId;
    String roomName;
    String category;
    String guestName;
    String email;
    String checkIn;
    String checkOut;
    double totalPrice;
    String paymentStatus;

    public Booking(String id, int roomId, String roomName, String category, String guestName, String email, String checkIn, String checkOut, double totalPrice, String paymentStatus) {
        this.id = id;
        this.roomId = roomId;
        this.roomName = roomName;
        this.category = category;
        this.guestName = guestName;
        this.email = email;
        this.checkIn = checkIn;
        this.checkOut = checkOut;
        this.totalPrice = totalPrice;
        this.paymentStatus = paymentStatus;
    }
}
