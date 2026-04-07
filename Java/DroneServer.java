import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.OutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.net.InetSocketAddress;
import java.util.Locale;
import java.awt.Desktop;
import java.net.URI;

interface FlightController {
    void processCommand(String command) throws Exception;
    String getTelemetryJSON();
    void physicsTick();
}

abstract class BaseDrone implements FlightController {
    protected volatile boolean engineOn = false;
    protected volatile double altitude = 0.0;
    protected volatile double speed = 0.0;
    protected volatile double gimbal = 0.0;
    protected volatile double battery = 100.0;
    
    protected volatile boolean recording = false;
    protected volatile boolean hoverLock = false;
    protected volatile boolean rthMode = false;
    protected volatile boolean mapActive = false;
    protected volatile boolean radarActive = false;
}

class ParakkumThalika extends BaseDrone {

    @Override
    public void processCommand(String cmd) throws Exception {
        if (cmd == null || cmd.trim().isEmpty()) {
            throw new IllegalArgumentException("Empty command received");
        }

        if (cmd.contains("ENGINE_TOGGLE")) {
            engineOn = !engineOn;
            if (!engineOn) speed = 0;
        } else if (cmd.contains("SHUTDOWN_PROGRAM")) {
            System.out.println("🛑 SYSTEM SHUTDOWN INITIATED.");
            System.exit(0);
        } else if (cmd.contains("ALTITUDE_UP")) {
            if (engineOn && !hoverLock && !rthMode && altitude < 500) {
                altitude += 5;
                speed = 15 + Math.random() * 5;
            }
        } else if (cmd.contains("ALTITUDE_DOWN")) {
            if (engineOn && !hoverLock && !rthMode) {
                altitude -= 5;
                if (altitude < 0) altitude = 0;
            }
        } else if (cmd.contains("GIMBAL_UP")) {
            if (gimbal < 30) gimbal += 5;
        } else if (cmd.contains("GIMBAL_DOWN")) {
            if (gimbal > -70) gimbal -= 5;
        } else if (cmd.contains("TOGGLE_VIDEO")) {
            if (engineOn) recording = !recording;
        } else if (cmd.contains("TOGGLE_MAP")) {
            mapActive = !mapActive;
        } else if (cmd.contains("TOGGLE_RADAR")) {
            radarActive = !radarActive;
        } else if (cmd.contains("TOGGLE_HOVER")) {
            if (engineOn && altitude > 0 && !rthMode) hoverLock = !hoverLock;
        } else if (cmd.contains("TOGGLE_RTH")) {
            if (engineOn && altitude > 0) {
                rthMode = !rthMode;
                if(rthMode) hoverLock = false;
            }
        }
    }

    @Override
    public void physicsTick() {
        if (engineOn && !hoverLock && !rthMode) {
            if (altitude > 0) {
                speed = Math.max(0, speed + (Math.random() * 4 - 2));
            } else {
                speed = 0;
            }
            if (Math.random() > 0.8) {
                battery = Math.max(0, battery - 0.1);
            }
        } else if (engineOn && hoverLock) {
            speed = Math.random() * 1; 
        } else {
            speed = 0;
        }

        if (rthMode) {
            if (altitude > 0) {
                altitude -= 5;
                speed = 25;
                if (altitude <= 0) {
                    altitude = 0;
                    rthMode = false;
                    engineOn = false; 
                    System.out.println("🛬 RTH Complete. Engine off.");
                }
            }
        }
    }

    @Override
    public String getTelemetryJSON() {
        return String.format(Locale.US, 
            "{\"engineOn\":%b,\"altitude\":%.1f,\"speed\":%.1f,\"gimbal\":%.1f,\"battery\":%.1f,\"recording\":%b,\"hoverLock\":%b,\"rthMode\":%b,\"mapActive\":%b,\"radarActive\":%b}",
            engineOn, altitude, speed, gimbal, battery, recording, hoverLock, rthMode, mapActive, radarActive);
    }
}

public class DroneServer {
    
    static final FlightController activeDrone = new ParakkumThalika();

    public static void main(String[] args) {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);

            server.createContext("/", (HttpExchange t) -> {
                try {
                    byte[] bytes = Files.readAllBytes(Paths.get("jackpot.html"));
                    t.getResponseHeaders().set("Content-Type", "text/html");
                    t.sendResponseHeaders(200, bytes.length);
                    OutputStream os = t.getResponseBody();
                    os.write(bytes);
                    os.close();
                } catch (IOException e) {
                    String error = "404: jackpot.html not found! Make sure it is in the same folder.";
                    t.sendResponseHeaders(404, error.length());
                    t.getResponseBody().write(error.getBytes());
                    t.getResponseBody().close();
                }
            });

            server.createContext("/api/status", (HttpExchange t) -> {
                String json = activeDrone.getTelemetryJSON();
                t.getResponseHeaders().set("Content-Type", "application/json");
                t.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
                t.sendResponseHeaders(200, json.length());
                OutputStream os = t.getResponseBody();
                os.write(json.getBytes());
                os.close();
            });

            server.createContext("/api/command", (HttpExchange t) -> {
                if ("OPTIONS".equalsIgnoreCase(t.getRequestMethod())) {
                    t.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
                    t.getResponseHeaders().set("Access-Control-Allow-Methods", "POST, OPTIONS");
                    t.sendResponseHeaders(204, -1);
                    return;
                }

                if ("POST".equalsIgnoreCase(t.getRequestMethod())) {
                    try {
                        InputStream is = t.getRequestBody();
                        String command = new String(is.readAllBytes());
                        
                        activeDrone.processCommand(command);

                        String response = "OK";
                        t.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
                        t.sendResponseHeaders(200, response.length());
                        t.getResponseBody().write(response.getBytes());
                    } catch (Exception e) {
                        System.out.println("Command Error: " + e.getMessage());
                    } finally {
                        t.getResponseBody().close();
                    }
                }
            });

            server.setExecutor(null); 
            server.start();
            System.out.println("✅ Drone Server Online on Port 8080");

            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(new URI("http://localhost:8080"));
            }

            new Thread(() -> {
                while (true) {
                    try {
                        activeDrone.physicsTick();
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
            }).start();

        } catch (Exception e) {
            System.out.println("CRITICAL FAILURE: " + e.getMessage());
        }
    }
}